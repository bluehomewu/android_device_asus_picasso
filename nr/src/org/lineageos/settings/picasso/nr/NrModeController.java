/*
 * SPDX-License-Identifier: Apache-2.0
 * SPDX-FileCopyrightText: The LineageOS Project
 */
package org.lineageos.settings.picasso.nr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-subscription NR preference controller. This controls SA/NSA configuration, not the
 * allowed-RAT bitmap or Android's temporary N1 mode API.
 *
 * All work, callbacks and listener notifications run on Scheduler's serial event loop.
 * Backend calls must enqueue their work without blocking that loop. The backend must have
 * exclusive ownership of the QTI callback pair and disable the ASUS global NR override.
 */
public final class NrModeController {
    public static final int MODE_SA_NSA = 0;
    public static final int MODE_NSA = 1;
    public static final int MODE_SA = 2;
    public static final int DEFAULT_MODE = MODE_SA_NSA;
    public static final long REQUEST_TIMEOUT_MS = 20_000;
    public static final long VERIFY_RETRY_DELAY_MS = 1_000;
    public static final int MAX_VERIFY_QUERIES = 4;

    public interface Callback {
        /** error == 0 means modem success. mode is ignored for a set response. */
        void onResult(int serial, int error, int mode);
        void onFailure(int serial, String reason);
    }

    public interface Backend {
        /** Recheck the expected subscription/slot identity immediately before dispatch. */
        void query(int subscriptionId, int slotId, int serial, Callback callback);
        void set(int subscriptionId, int slotId, int serial, int mode, Callback callback);
        /** Cancel queued dispatch only; an already transmitted modem command is not revoked. */
        default void cancel(int slotId, int serial) {}
    }

    public interface Cancellable {
        void cancel();
    }

    public interface Scheduler {
        /** Queue work on a serial, non-reentrant event loop; callable from any thread. */
        void execute(Runnable work);
        /** Run work on the same event loop after delayMs. */
        Cancellable schedule(long delayMs, Runnable work);
    }

    public interface Store {
        int readMode(int subscriptionId, int defaultMode);
        /** Return false, or throw, if the preference could not be durably saved. */
        boolean writeMode(int subscriptionId, int mode);
    }

    public interface Listener {
        void onState(State state);
        default void onRemoved(int subscriptionId) {}
    }

    public enum Status { WAITING, QUERYING, SETTING, VERIFYING, APPLIED, ERROR }

    public static final class Sim {
        public final int subscriptionId;
        /** Zero-based logical radio slot, not a subscription ID or physical-card number. */
        public final int slotId;

        public Sim(int subscriptionId, int slotId) {
            if (subscriptionId < 0 || slotId < 0) {
                throw new IllegalArgumentException("Invalid SIM mapping");
            }
            this.subscriptionId = subscriptionId;
            this.slotId = slotId;
        }
    }

    public static final class State {
        public final Sim sim;
        public final int subscriptionId;
        public final int slotId;
        public final int desiredMode;
        /** Last successful query/configuration report, not evidence of SA registration. */
        public final Integer verifiedMode;
        public final Status status;
        public final String detail;

        private State(Entry entry) {
            sim = entry.sim;
            subscriptionId = sim.subscriptionId;
            slotId = sim.slotId;
            desiredMode = entry.desired;
            verifiedMode = entry.verified;
            status = entry.status;
            detail = entry.detail;
        }
    }

    private enum Kind { INITIAL_QUERY, SET, VERIFY_QUERY }

    private static final class Entry {
        final Sim sim;
        int desired = DEFAULT_MODE;
        Integer verified;
        boolean preferencesLoaded;
        boolean driftRetryUsed;
        long revision;
        int verificationQueries;
        Status status = Status.WAITING;
        String detail = "Waiting for radio backend";
        Object delayToken;
        Cancellable delay;

        Entry(Sim sim) { this.sim = sim; }
    }

    private static final class Slot {
        boolean connected;
        Pending pending;
    }

    private static final class Pending {
        final Entry owner;
        final long revision;
        final int serial;
        final int target;
        final Kind kind;
        Cancellable timeout;

        Pending(Entry owner, int serial, Kind kind) {
            this.owner = owner;
            revision = owner.revision;
            this.serial = serial;
            target = owner.desired;
            this.kind = kind;
        }
    }

    private final Backend backend;
    private final Scheduler scheduler;
    private final Store store;
    private final Listener listener;
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private final Map<Integer, Slot> slots = new HashMap<>();
    // Never wrap/reuse serials, including controller recreation in the same process.
    private static final AtomicLong NEXT_SERIAL = new AtomicLong(1);
    private boolean closed;

    public NrModeController(Backend backend, Scheduler scheduler, Store store, Listener listener) {
        this.backend = Objects.requireNonNull(backend);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.store = Objects.requireNonNull(store);
        this.listener = Objects.requireNonNull(listener);
    }

    /** Replace the active logical subscription mapping. Unchanged entries are not retried. */
    public void replaceSims(List<Sim> sims) {
        Objects.requireNonNull(sims);
        List<Sim> copy = new ArrayList<>(sims);
        Set<Integer> subscriptions = new HashSet<>();
        Set<Integer> slotIds = new HashSet<>();
        for (Sim sim : copy) {
            Objects.requireNonNull(sim);
            if (!subscriptions.add(sim.subscriptionId) || !slotIds.add(sim.slotId)) {
                throw new IllegalArgumentException("Duplicate SIM mapping");
            }
        }
        scheduler.execute(() -> replaceSimsOnLoop(copy));
    }

    public void setMode(int subscriptionId, int mode) {
        checkMode(mode);
        scheduler.execute(() -> {
            Entry entry = entries.get(subscriptionId);
            if (closed || entry == null) return;
            try {
                if (!store.writeMode(subscriptionId, mode)) {
                    entry.revision++;
                    fail(entry, "Could not save preference");
                    return;
                }
            } catch (RuntimeException failure) {
                entry.revision++;
                fail(entry, "Could not save preference");
                return;
            }
            entry.desired = mode;
            entry.preferencesLoaded = true;
            retry(entry);
        });
    }

    /** Explicit user retry. Errors do not trigger automatic retry loops. */
    public void refresh(int subscriptionId) {
        scheduler.execute(() -> {
            Entry entry = entries.get(subscriptionId);
            if (!closed && entry != null) retry(entry);
        });
    }

    public void onBackendConnected(int slotId) {
        checkSlot(slotId);
        scheduler.execute(() -> {
            if (closed) return;
            Slot slot = slot(slotId);
            if (slot.connected) return;
            slot.connected = true;
            Entry entry = entryForSlot(slotId);
            if (entry != null) retry(entry);
        });
    }

    public void onBackendDied(int slotId) {
        checkSlot(slotId);
        scheduler.execute(() -> {
            if (closed) return;
            Slot slot = slot(slotId);
            slot.connected = false;
            if (slot.pending != null) {
                cancelQueued(slot.pending);
                slot.pending.timeout.cancel();
                slot.pending = null;
            }
            Entry entry = entryForSlot(slotId);
            if (entry != null) {
                entry.revision++;
                cancelDelay(entry);
                entry.verified = null;
                update(entry, Status.WAITING, "Radio backend disconnected");
            }
        });
    }

    /**
     * Accept a real modem NR-configuration report, never a 5G icon/registration technology.
     * Permit one bounded reconciliation of drift per user/SIM/reconnect epoch. A previous
     * failed set cannot be converted into APPLIED by a later unsolicited report.
     */
    public void onNrModeChanged(int slotId, int mode) {
        checkSlot(slotId);
        checkMode(mode);
        scheduler.execute(() -> {
            Entry entry = entryForSlot(slotId);
            if (closed || entry == null || !slot(slotId).connected) return;
            entry.verified = mode;
            if (slot(slotId).pending != null || entry.delayToken != null
                    || entry.status != Status.APPLIED || mode == entry.desired) {
                publish(entry);
                return;
            }
            if (entry.driftRetryUsed) {
                fail(entry, "Modem configuration changed again; retry manually");
                return;
            }
            entry.driftRetryUsed = true;
            entry.revision++;
            reconcile(entry);
        });
    }

    /** Stop and forget callbacks without issuing any modem command. */
    public void close() {
        scheduler.execute(() -> {
            if (closed) return;
            closed = true;
            for (Entry entry : entries.values()) cancelDelay(entry);
            for (Slot slot : slots.values()) {
                if (slot.pending != null) {
                    cancelQueued(slot.pending);
                    slot.pending.timeout.cancel();
                }
                slot.pending = null;
            }
            List<Integer> removed = new ArrayList<>(entries.keySet());
            entries.clear();
            for (int subscriptionId : removed) listener.onRemoved(subscriptionId);
        });
    }

    private void replaceSimsOnLoop(List<Sim> sims) {
        if (closed) return;
        Map<Integer, Entry> replacement = new LinkedHashMap<>();
        List<Entry> added = new ArrayList<>();
        for (Sim sim : sims) {
            Entry entry = entries.get(sim.subscriptionId);
            if (entry == null || entry.sim.slotId != sim.slotId) {
                entry = new Entry(sim);
                try {
                    int mode = store.readMode(sim.subscriptionId, DEFAULT_MODE);
                    checkMode(mode);
                    entry.desired = mode;
                    entry.preferencesLoaded = true;
                } catch (RuntimeException failure) {
                    entry.status = Status.ERROR;
                    entry.detail = "Could not read a valid saved preference";
                }
                added.add(entry);
            }
            replacement.put(sim.subscriptionId, entry);
        }
        List<Integer> removed = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (replacement.get(entry.sim.subscriptionId) != entry) {
                cancelDelay(entry);
                entry.revision++;
                removed.add(entry.sim.subscriptionId);
                // Keep the slot's outstanding request until response/timeout drains it.
                // Ignoring its callback does not cancel a modem write already in flight.
            }
        }
        entries.clear();
        entries.putAll(replacement);
        for (int subscriptionId : removed) listener.onRemoved(subscriptionId);
        for (Entry entry : added) reconcile(entry);
    }

    private void retry(Entry entry) {
        entry.revision++;
        entry.driftRetryUsed = false;
        reconcile(entry);
    }

    private void reconcile(Entry entry) {
        cancelDelay(entry);
        entry.verificationQueries = 0;
        if (!entry.preferencesLoaded) {
            fail(entry, "Could not read a valid saved preference");
        } else if (!slot(entry.sim.slotId).connected) {
            update(entry, Status.WAITING, "Waiting for radio backend");
        } else if (slot(entry.sim.slotId).pending != null) {
            update(entry, Status.WAITING, "Waiting for previous slot request");
        } else {
            issue(entry, Kind.INITIAL_QUERY);
        }
    }

    private void issue(Entry entry, Kind kind) {
        long serial = NEXT_SERIAL.getAndIncrement();
        if (serial > Integer.MAX_VALUE) {
            fail(entry, "Request serial space exhausted; restart backend");
            return;
        }
        Slot slot = slot(entry.sim.slotId);
        if (slot.pending != null) throw new IllegalStateException("Concurrent slot request");
        Pending pending = new Pending(entry, (int) serial, kind);
        slot.pending = pending;
        if (kind == Kind.VERIFY_QUERY) entry.verificationQueries++;
        pending.timeout = scheduler.schedule(REQUEST_TIMEOUT_MS, () -> {
            if (closed || slot.pending != pending) return;
            cancelQueued(pending);
            complete(pending, -1, -1, "Radio request timed out");
        });
        update(entry, kind == Kind.SET ? Status.SETTING
                        : kind == Kind.VERIFY_QUERY ? Status.VERIFYING : Status.QUERYING,
                kind == Kind.SET ? "Applying saved preference" : "Reading modem configuration");
        Callback callback = new Callback() {
            @Override
            public void onResult(int serial, int error, int mode) {
                scheduler.execute(() -> {
                    if (serial == pending.serial) complete(pending, error, mode, null);
                });
            }

            @Override
            public void onFailure(int serial, String reason) {
                scheduler.execute(() -> {
                    if (serial == pending.serial) {
                        complete(pending, -1, -1,
                                reason == null ? "Radio transport failure" : reason);
                    }
                });
            }
        };
        try {
            if (kind == Kind.SET) {
                backend.set(entry.sim.subscriptionId, entry.sim.slotId, pending.serial,
                        pending.target, callback);
            } else {
                backend.query(entry.sim.subscriptionId, entry.sim.slotId, pending.serial, callback);
            }
        } catch (RuntimeException failure) {
            complete(pending, -1, -1, "Radio transport failure");
        }
    }

    private void complete(Pending pending, int error, int mode, String failure) {
        if (closed) return;
        Slot slot = slot(pending.owner.sim.slotId);
        if (slot.pending != pending) return;
        pending.timeout.cancel();
        slot.pending = null;
        Entry entry = entryForSlot(pending.owner.sim.slotId);
        if (entry != pending.owner || entry.revision != pending.revision) {
            // A newer explicit user request or SIM mapping superseded this operation.
            if (entry != null && entry.status != Status.ERROR) reconcile(entry);
            return;
        }
        if (failure != null || error != 0) {
            fail(entry, failure != null ? failure : "Modem error " + error);
            return;
        }
        if (pending.kind == Kind.SET) {
            issue(entry, Kind.VERIFY_QUERY);
            return;
        }
        if (!isMode(mode)) {
            fail(entry, "Modem returned an invalid NR configuration");
            return;
        }
        entry.verified = mode;
        if (mode == entry.desired) {
            update(entry, Status.APPLIED, "Confirmed by modem query");
        } else if (pending.kind == Kind.INITIAL_QUERY) {
            issue(entry, Kind.SET);
        } else if (entry.verificationQueries >= MAX_VERIFY_QUERIES) {
            fail(entry, "Modem did not confirm the saved preference");
        } else {
            Object token = new Object();
            entry.delayToken = token;
            entry.delay = scheduler.schedule(VERIFY_RETRY_DELAY_MS, () -> {
                if (closed || entries.get(entry.sim.subscriptionId) != entry
                        || entry.delayToken != token) return;
                entry.delay = null;
                entry.delayToken = null;
                issue(entry, Kind.VERIFY_QUERY);
            });
            update(entry, Status.VERIFYING, "Waiting for modem configuration update");
        }
    }

    private void fail(Entry entry, String detail) {
        cancelDelay(entry);
        update(entry, Status.ERROR, detail);
    }

    private void cancelQueued(Pending pending) {
        try {
            backend.cancel(pending.owner.sim.slotId, pending.serial);
        } catch (RuntimeException ignored) {
            // Failure to cancel must not suppress timeout/death/close bookkeeping.
        }
    }

    private void cancelDelay(Entry entry) {
        if (entry.delay != null) entry.delay.cancel();
        entry.delay = null;
        entry.delayToken = null;
    }

    private void update(Entry entry, Status status, String detail) {
        entry.status = status;
        entry.detail = detail;
        publish(entry);
    }

    private void publish(Entry entry) { listener.onState(new State(entry)); }

    private Slot slot(int slotId) {
        Slot slot = slots.get(slotId);
        if (slot == null) {
            slot = new Slot();
            slots.put(slotId, slot);
        }
        return slot;
    }

    private Entry entryForSlot(int slotId) {
        for (Entry entry : entries.values()) {
            if (entry.sim.slotId == slotId) return entry;
        }
        return null;
    }

    private static boolean isMode(int mode) { return mode >= MODE_SA_NSA && mode <= MODE_SA; }

    private static void checkMode(int mode) {
        if (!isMode(mode)) throw new IllegalArgumentException("Invalid NR mode");
    }

    private static void checkSlot(int slotId) {
        if (slotId < 0) throw new IllegalArgumentException("Invalid radio slot");
    }
}
