/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.picasso.nr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.os.UserManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArraySet;

/** Android lifecycle, storage and asynchronous transport adapters. No RAT-bitmap writes. */
final class NrManager implements NrModeController.Backend {
    private static final String TAG = "PicassoNr";
    private static final String LEGACY_PROPERTY = "persist.radio.tel.5g_sa_off";
    private static final int MAX_CONNECT_ATTEMPTS = 5;

    interface Listener {
        void onChanged(View view);
    }

    static final class View {
        final List<SubscriptionInfo> sims;
        final Map<Integer, NrModeController.State> states;
        final String error;
        View(List<SubscriptionInfo> sims, Map<Integer, NrModeController.State> states,
                String error) {
            this.sims = Collections.unmodifiableList(new ArrayList<>(sims));
            this.states = Collections.unmodifiableMap(new HashMap<>(states));
            this.error = error;
        }
    }

    private final Context mContext;
    private final Handler mLoop;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Set<Listener> mListeners = new CopyOnWriteArraySet<>();
    private final SharedPreferences mPrefs;
    private NrPreferences mPreferences;
    private final SubscriptionManager mSubscriptions;
    private final TelephonyManager mTelephony;
    private final NrModeController mController;
    private final ExecutorService[] mIo = new ExecutorService[] {
        Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor()
    };
    private final Map<Integer, NrModeController.State> mStates = new HashMap<>();
    private final Map<Integer, Connection> mConnections = new HashMap<>();
    private final Map<Integer, RadioListener> mRadioListeners = new HashMap<>();
    private final int[] mConnectAttempts = new int[2];
    private List<SubscriptionInfo> mSims = Collections.emptyList();
    private String mError;
    private volatile boolean mReady;
    private volatile View mView = new View(Collections.emptyList(), Collections.emptyMap(), null);

    NrManager(Context context) {
        mContext = context;
        HandlerThread thread = new HandlerThread("PicassoNrState");
        thread.start();
        mLoop = new Handler(thread.getLooper());
        mPrefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("nr_modes", Context.MODE_PRIVATE);
        mSubscriptions = context.getSystemService(SubscriptionManager.class);
        mTelephony = context.getSystemService(TelephonyManager.class);
        mController = new NrModeController(this, new NrModeController.Scheduler() {
            @Override public void execute(Runnable work) { mLoop.post(work); }
            @Override public NrModeController.Cancellable schedule(long delay, Runnable work) {
                mLoop.postDelayed(work, delay);
                return () -> mLoop.removeCallbacks(work);
            }
        }, new NrModeController.Store() {
            @Override public int readMode(int sub, int defaultMode) {
                return mPreferences.readMode(sub, defaultMode);
            }
            @Override public boolean writeMode(int sub, int mode) {
                return mPreferences.writeMode(sub, mode);
            }
        }, new NrModeController.Listener() {
            @Override public void onState(NrModeController.State state) {
                mStates.put(state.subscriptionId, state);
                // Deliberately exclude SIM identifiers, names and subscription IDs.
                Log.i(TAG, "slot=" + state.slotId + " desired=" + state.desiredMode
                        + " confirmed=" + state.verifiedMode + " status=" + state.status
                        + " detail=" + state.detail);
                publish();
            }
            @Override public void onRemoved(int sub) { mStates.remove(sub); publish(); }
        });
        mSubscriptions.addOnSubscriptionsChangedListener(mLoop::post,
                new SubscriptionManager.OnSubscriptionsChangedListener() {
                    @Override public void onSubscriptionsChanged() { updateSims(false); }
                });
        IntentFilter filter = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        filter.addAction(TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED);
        filter.addAction(UserManager.ACTION_USER_RESTRICTIONS_CHANGED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                // These are protected platform broadcasts. Never accept slot/mode
                // values from the broadcast; re-read SubscriptionManager instead.
                if (!Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(intent.getAction())
                        || !intent.getBooleanExtra("state", false)) updateSims(true);
            }
        }, filter, null, mLoop, Context.RECEIVER_EXPORTED);
        mLoop.post(() -> updateSims(false));
    }

    View view() { return mView; }
    void refresh() { mLoop.post(() -> updateSims(true)); }

    void addListener(Listener listener) {
        mListeners.add(listener);
        mMain.post(() -> {
            if (mListeners.contains(listener)) listener.onChanged(mView);
        });
    }

    void removeListener(Listener listener) { mListeners.remove(listener); }

    void setMode(int sub, int mode) {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("Invalid NR mode");
        mLoop.post(() -> {
            // Re-resolve current mappings before honoring a dialog opened earlier.
            updateSims(false, sub);
            if (mReady && mSims.stream().anyMatch(s -> s.getSubscriptionId() == sub)) {
                // A deliberate selection also retries a previously exhausted
                // connection, without resetting the other SIM's retry budget.
                for (SubscriptionInfo sim : mSims) if (sim.getSubscriptionId() == sub) {
                    int slot = sim.getSimSlotIndex();
                    mConnectAttempts[slot] = 0;
                    ensureConnected(slot);
                }
                mController.setMode(sub, mode);
            }
        });
    }

    private void updateSims(boolean retry) {
        updateSims(retry, SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    private void updateSims(boolean retry, int connectSubscription) {
        try {
            if (mContext.getSystemService(UserManager.class)
                    .hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)) {
                throw new SecurityException("Mobile network configuration is restricted");
            }
            List<SubscriptionInfo> active = mSubscriptions.getActiveSubscriptionInfoList();
            List<SubscriptionInfo> current = new ArrayList<>();
            Set<Integer> slots = new HashSet<>();
            if (active != null) for (SubscriptionInfo sim : active) {
                int sub = sim.getSubscriptionId();
                int slot = SubscriptionManager.getPhoneId(sub);
                if (!SubscriptionManager.isValidSubscriptionId(sub) || slot < 0 || slot > 1
                        || slot != sim.getSimSlotIndex() || !slots.add(slot)) {
                    throw new IllegalStateException("Unsupported or changing SIM mapping");
                }
                current.add(sim);
            }
            current.sort(Comparator.comparingInt(SubscriptionInfo::getSimSlotIndex));
            for (SubscriptionInfo sim : current) {
                boolean sameMapping = mSims.stream().anyMatch(old ->
                        old.getSubscriptionId() == sim.getSubscriptionId()
                        && old.getSimSlotIndex() == sim.getSimSlotIndex());
                if (!sameMapping) mConnectAttempts[sim.getSimSlotIndex()] = 0;
            }
            mSims = current;
            prepareLegacyOverride(current);
            mReady = true;
            mError = null;
            List<NrModeController.Sim> mappings = new ArrayList<>();
            for (SubscriptionInfo sim : current) {
                mappings.add(new NrModeController.Sim(sim.getSubscriptionId(), sim.getSimSlotIndex()));
            }
            mController.replaceSims(mappings);
            for (int slot : new ArrayList<>(mConnections.keySet())) {
                Connection connection = mConnections.get(slot);
                if (!slots.contains(slot)) {
                    disconnect(connection);
                } else {
                    for (SubscriptionInfo sim : current) if (sim.getSimSlotIndex() == slot) {
                        // Also invalidate queued I/O before controller's mapping task runs.
                        if (connection.subscriptionId != sim.getSubscriptionId()) {
                            connection.mappingEpoch++;
                            connection.subscriptionId = sim.getSubscriptionId();
                        }
                    }
                }
            }
            for (int slot : slots) {
                if (connectSubscription >= 0 && current.stream().noneMatch(s ->
                        s.getSubscriptionId() == connectSubscription
                                && s.getSimSlotIndex() == slot)) continue;
                if (retry) mConnectAttempts[slot] = 0;
                ensureConnected(slot);
            }
            updateRadioListeners(current);
            if (retry) for (SubscriptionInfo sim : current) {
                mController.refresh(sim.getSubscriptionId());
            }
        } catch (RuntimeException error) {
            mReady = false;
            mError = "SIM access or legacy override setup failed (" + error.getClass().getSimpleName() + ")";
            Log.e(TAG, mError); // No exception payload: it may contain identifiers.
            mController.replaceSims(Collections.emptyList());
            for (Connection connection : new ArrayList<>(mConnections.values())) disconnect(connection);
            updateRadioListeners(Collections.emptyList());
        }
        publish();
    }

    private void prepareLegacyOverride(List<SubscriptionInfo> sims) {
        if (mPreferences == null) mPreferences = new NrPreferences(new NrPreferences.Backing() {
            @Override public Map<String, ?> readAll() { return mPrefs.getAll(); }
            @Override public boolean commit(Map<String, Object> values) {
                // Full replacement forces a fresh disk write even after a failed
                // SharedPreferences.commit() changed its in-memory cache.
                SharedPreferences.Editor editor = mPrefs.edit().clear();
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                    else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                    else throw new IllegalArgumentException("Invalid saved preference type");
                }
                return editor.commit();
            }
        });
        if (sims.isEmpty()) return;
        int legacy = SystemProperties.getInt(LEGACY_PROPERTY, -2);
        if (legacy < -1 || legacy > 2) {
            throw new IllegalStateException("Invalid legacy NR preference");
        }
        List<Integer> active = new ArrayList<>();
        for (SubscriptionInfo sim : sims) active.add(sim.getSubscriptionId());
        if (!mPreferences.migrateLegacy(active, legacy)) {
            throw new IllegalStateException("Could not save legacy preferences");
        }
        if (legacy != -1) {
            // In this ASUS blob -1 means do not overwrite the per-slot NR config.
            // This alone does not enable SA: the controller still applies/reads it.
            SystemProperties.set(LEGACY_PROPERTY, "-1");
        }
        if (SystemProperties.getInt(LEGACY_PROPERTY, -2) != -1) {
            throw new IllegalStateException("Global NR override still active");
        }
    }

    private final class RadioListener extends TelephonyCallback
            implements TelephonyCallback.ServiceStateListener {
        final int sub;
        final int slot;
        final TelephonyManager phone;
        boolean poweredOff;
        RadioListener(SubscriptionInfo sim) {
            sub = sim.getSubscriptionId();
            slot = sim.getSimSlotIndex();
            phone = mTelephony.createForSubscriptionId(sub);
        }
        @Override public void onServiceStateChanged(ServiceState state) {
            if (mRadioListeners.get(sub) != this) return;
            boolean off = state.getState() == ServiceState.STATE_POWER_OFF;
            if (poweredOff && !off) {
                mConnectAttempts[slot] = 0;
                ensureConnected(slot);
                mController.refresh(sub);
            }
            poweredOff = off;
        }
    }

    private void updateRadioListeners(List<SubscriptionInfo> sims) {
        Map<Integer, SubscriptionInfo> active = new HashMap<>();
        for (SubscriptionInfo sim : sims) active.put(sim.getSubscriptionId(), sim);
        for (RadioListener old : new ArrayList<>(mRadioListeners.values())) {
            SubscriptionInfo sim = active.get(old.sub);
            if (sim == null || sim.getSimSlotIndex() != old.slot) {
                mRadioListeners.remove(old.sub);
                try {
                    old.phone.unregisterTelephonyCallback(old);
                } catch (RuntimeException unavailable) {
                    Log.w(TAG, "Phone service unavailable while removing listener");
                }
            }
        }
        for (SubscriptionInfo sim : sims) {
            if (!mRadioListeners.containsKey(sim.getSubscriptionId())) {
                RadioListener listener = new RadioListener(sim);
                listener.phone.registerTelephonyCallback(TelephonyManager.INCLUDE_LOCATION_DATA_NONE,
                        mLoop::post, listener);
                mRadioListeners.put(listener.sub, listener);
            }
        }
    }

    private boolean activeSlot(int slot) {
        return mReady && mSims.stream().anyMatch(s -> s.getSimSlotIndex() == slot);
    }

    private void ensureConnected(int slot) {
        if (!activeSlot(slot) || mConnections.containsKey(slot)
                || mConnectAttempts[slot] >= MAX_CONNECT_ATTEMPTS) return;
        mConnectAttempts[slot]++;
        Connection connection = new Connection(slot);
        for (SubscriptionInfo sim : mSims) if (sim.getSimSlotIndex() == slot) {
            connection.subscriptionId = sim.getSubscriptionId();
        }
        mConnections.put(slot, connection);
        // Synchronous service lookup/setCallback never blocks the state loop/UI.
        mIo[slot].execute(() -> {
            try {
                QtiNrRadio radio = QtiNrRadio.connect(slot, connection);
                mLoop.post(() -> {
                    if (mConnections.get(slot) != connection) {
                        mIo[slot].execute(radio::close);
                        return;
                    }
                    connection.radio = radio;
                    mConnectAttempts[slot] = 0;
                    mController.onBackendConnected(slot);
                });
            } catch (Exception error) {
                mLoop.post(() -> connectionLost(connection));
            }
        });
        mLoop.postDelayed(() -> {
            if (mConnections.get(slot) == connection && connection.radio == null) {
                connectionLost(connection);
            }
        }, NrModeController.REQUEST_TIMEOUT_MS);
    }

    private void connectionLost(Connection connection) {
        if (mConnections.get(connection.slot) != connection) return;
        disconnect(connection);
        Log.w(TAG, "QTI backend unavailable for slot=" + connection.slot);
        int attempt = mConnectAttempts[connection.slot];
        if (attempt < MAX_CONNECT_ATTEMPTS) {
            mLoop.postDelayed(() -> ensureConnected(connection.slot), Math.min(30_000, 2000L << attempt));
        }
    }

    private void disconnect(Connection connection) {
        if (mConnections.get(connection.slot) != connection) return;
        mConnections.remove(connection.slot);
        connection.valid = false;
        mController.onBackendDied(connection.slot);
        for (Request request : connection.pending.values()) request.cancelled = true;
        connection.pending.clear();
        if (connection.radio != null) mIo[connection.slot].execute(connection.radio::close);
    }

    private static final class Request {
        final boolean set;
        final NrModeController.Callback callback;
        final long deadline = SystemClock.elapsedRealtime() + NrModeController.REQUEST_TIMEOUT_MS;
        volatile boolean cancelled;
        Request(boolean set, NrModeController.Callback callback) {
            this.set = set;
            this.callback = callback;
        }
    }

    private final class Connection implements QtiNrRadio.Listener {
        final int slot;
        volatile boolean valid = true;
        volatile int subscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        volatile long mappingEpoch;
        final Map<Integer, Request> pending = new HashMap<>();
        QtiNrRadio radio;
        Connection(int slot) { this.slot = slot; }
        private void response(int serial, int error, int value, boolean set) {
            mLoop.post(() -> {
                if (mConnections.get(slot) != this) return;
                Request request = pending.get(serial);
                if (request == null || request.set != set) return;
                pending.remove(serial);
                request.cancelled = true;
                if (set && error == 0 && value != 1) {
                    request.callback.onFailure(serial, "QTI rejected NR preference (status " + value + ")");
                } else {
                    request.callback.onResult(serial, error, value);
                }
            });
        }
        @Override public void onSet(int serial, int error, int status) { response(serial, error, status, true); }
        @Override public void onQuery(int serial, int error, int mode) { response(serial, error, mode, false); }
        @Override public void onModeChanged(int mode) {
            mLoop.post(() -> {
                if (mConnections.get(slot) == this && mode >= 0 && mode <= 2) {
                    mController.onNrModeChanged(slot, mode);
                }
            });
        }
        @Override public void onDeath() { mLoop.post(() -> connectionLost(this)); }
    }

    @Override public void query(int sub, int slot, int serial, NrModeController.Callback callback) {
        send(sub, slot, serial, null, callback);
    }
    @Override public void set(int sub, int slot, int serial, int mode, NrModeController.Callback callback) {
        send(sub, slot, serial, mode, callback);
    }
    @Override public void cancel(int slot, int serial) {
        Connection connection = mConnections.get(slot);
        if (connection == null) return;
        Request request = connection.pending.remove(serial);
        // Stops queued I/O only. A request already transmitted to the modem
        // cannot be revoked; later responses are still rejected by identity.
        if (request != null) request.cancelled = true;
    }
    private void send(int sub, int slot, int serial, Integer mode, NrModeController.Callback callback) {
        Connection connection = mConnections.get(slot);
        if (!mReady || connection == null || connection.radio == null
                || connection.subscriptionId != sub) {
            callback.onFailure(serial, "QTI backend not connected");
            return;
        }
        if (connection.pending.size() >= 8) {
            callback.onFailure(serial, "Too many pending radio requests");
            return;
        }
        Request request = new Request(mode != null, callback);
        long mappingEpoch = connection.mappingEpoch;
        connection.pending.put(serial, request);
        mLoop.postDelayed(() -> {
            request.cancelled = true;
            connection.pending.remove(serial, request);
        }, NrModeController.REQUEST_TIMEOUT_MS);
        QtiNrRadio radio = connection.radio;
        mIo[slot].execute(() -> {
            try {
                // A queued request carries its original subscription identity.
                // Recheck authoritative mapping and policy just before dispatch;
                // never reinterpret an old request using the replacement SIM.
                SubscriptionInfo active = mSubscriptions.getActiveSubscriptionInfo(sub);
                int currentPhone = SubscriptionManager.getPhoneId(sub);
                boolean restricted = mContext.getSystemService(UserManager.class)
                        .hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS);
                int legacy = SystemProperties.getInt(LEGACY_PROPERTY, -2);
                if (!mReady || !connection.valid || connection.subscriptionId != sub
                        || connection.mappingEpoch != mappingEpoch || request.cancelled
                        || SystemClock.elapsedRealtime() >= request.deadline
                        || active == null || active.getSubscriptionId() != sub
                        || active.getSimSlotIndex() != slot || currentPhone != slot
                        || restricted || legacy != -1) {
                    throw new IllegalStateException("SIM mapping or policy changed");
                }
            } catch (RuntimeException stale) {
                mLoop.post(() -> {
                    connection.pending.remove(serial);
                    callback.onFailure(serial, "SIM mapping or policy changed before dispatch");
                });
                return;
            }
            try {
                if (mode == null) radio.query(serial); else radio.set(serial, mode);
            } catch (Exception failure) {
                mLoop.post(() -> {
                    if (mConnections.get(slot) != connection) return;
                    connection.pending.remove(serial);
                    callback.onFailure(serial, "QTI transport failure");
                    connectionLost(connection);
                });
            }
        });
    }

    private void publish() {
        mView = new View(mSims, mStates, mError);
        // The UI lives in this process: no exported provider or Binder API is
        // needed. Read the latest immutable snapshot on the main thread and
        // recheck registration so a stopped Activity receives no stale updates.
        for (Listener listener : mListeners) mMain.post(() -> {
            if (mListeners.contains(listener)) listener.onChanged(mView);
        });
    }
}
