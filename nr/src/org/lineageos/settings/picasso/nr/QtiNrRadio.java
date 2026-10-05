/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.picasso.nr;

import android.os.HwBinder;
import android.os.HwBlob;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.IHwInterface;
import android.os.Process;
import android.os.RemoteException;

import java.util.ArrayList;

/**
 * Narrow client for the shipped ASUS QTI radio 2.5 NR preference interface.
 *
 * This is not an implementation of Android's temporary N1-mode API. The wire
 * contract was checked against the generated interfaces in ASUS
 * 18.1055.2307.269 qti-telephony-common.jar (SHA-256
 * eb7800519c145eaf4f69948753a83d74fb0e402cda6170763adf20dfdda01911).
 * No old telephony framework classes are loaded. One owner per slot must hold
 * this connection: QTI setCallback replaces, rather than multiplexes, clients.
 */
final class QtiNrRadio implements AutoCloseable {
    static final String RADIO = "vendor.qti.hardware.radio.qtiradio@2.5::IQtiRadio";
    private static final String RADIO_BASE =
            "vendor.qti.hardware.radio.qtiradio@1.0::IQtiRadio";
    private static final String RESPONSE =
            "vendor.qti.hardware.radio.qtiradio@2.5::IQtiRadioResponse";
    private static final String INDICATION =
            "vendor.qti.hardware.radio.qtiradio@2.5::IQtiRadioIndication";
    private static final String BASE = "android.hidl.base@1.0::IBase";
    private static final int INTERFACE_CHAIN = 256067662;
    private static final int DEBUG = 256131655;
    private static final int INTERFACE_DESCRIPTOR = 256136003;
    private static final int HASH_CHAIN = 256398152;
    private static final int INSTRUMENTATION = 256462420;
    private static final int PING = 256921159;
    private static final int DEBUG_INFO = 257049926;
    private static final int SYSPROPS_CHANGED = 257120595;

    interface Listener {
        void onSet(int serial, int error, int status);
        void onQuery(int serial, int error, int mode);
        void onModeChanged(int mode);
        void onDeath();
    }

    private final IHwBinder mRemote;
    private final IHwBinder.DeathRecipient mDeath;
    private final CallbackBinder mResponse;
    private final CallbackBinder mIndication;

    static QtiNrRadio connect(int slot, Listener listener) throws RemoteException {
        if (slot < 0 || slot > 1) throw new IllegalArgumentException("Invalid logical slot");
        IHwBinder remote = HwBinder.getService(RADIO, "slot" + (slot + 1), false);
        if (remote == null) throw new RemoteException("QTI radio unavailable");
        HwParcel request = new HwParcel();
        HwParcel reply = new HwParcel();
        try {
            request.writeInterfaceToken(BASE);
            remote.transact(INTERFACE_CHAIN, request, reply, 0);
            reply.verifySuccess();
            if (!reply.readStringVector().contains(RADIO)) {
                throw new RemoteException("QTI radio 2.5 unavailable");
            }
        } finally {
            request.releaseTemporaryStorage();
            reply.release();
        }
        return new QtiNrRadio(remote, listener);
    }

    private QtiNrRadio(IHwBinder remote, Listener listener) throws RemoteException {
        mRemote = remote;
        mDeath = cookie -> listener.onDeath();
        mResponse = new CallbackBinder(true, listener);
        mIndication = new CallbackBinder(false, listener);
        if (!remote.linkToDeath(mDeath, 0)) {
            throw new RemoteException("Cannot monitor QTI radio death");
        }
        HwParcel request = new HwParcel();
        HwParcel reply = new HwParcel();
        try {
            request.writeInterfaceToken(RADIO_BASE);
            request.writeStrongBinder(mResponse);
            request.writeStrongBinder(mIndication);
            remote.transact(1, request, reply, 0);
            reply.verifySuccess();
        } catch (RemoteException | RuntimeException e) {
            remote.unlinkToDeath(mDeath);
            throw e;
        } finally {
            request.releaseTemporaryStorage();
            reply.release();
        }
    }

    void query(int serial) throws RemoteException {
        transact(21, serial, null);
    }

    void set(int serial, int mode) throws RemoteException {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("Invalid NR mode");
        transact(20, serial, mode);
    }

    private void transact(int code, int serial, Integer mode) throws RemoteException {
        HwParcel request = new HwParcel();
        HwParcel reply = new HwParcel();
        try {
            request.writeInterfaceToken(RADIO);
            request.writeInt32(serial);
            if (mode != null) request.writeInt32(mode);
            mRemote.transact(code, request, reply, 1);
        } finally {
            request.releaseTemporaryStorage();
            reply.release();
        }
    }

    @Override
    public void close() {
        mRemote.unlinkToDeath(mDeath);
        // Do not setCallback(null, null): a replacement connection may own it now.
    }

    private static final class CallbackBinder extends HwBinder implements IHwInterface {
        private final boolean mIsResponse;
        private final Listener mListener;
        private final ArrayList<String> mChain;
        private final String[] mHashes;

        CallbackBinder(boolean response, Listener listener) {
            mIsResponse = response;
            mListener = listener;
            String type = response ? "IQtiRadioResponse" : "IQtiRadioIndication";
            String[] versions = response
                    ? new String[] {"2.5", "2.4", "2.3", "2.2", "2.1", "2.0", "1.0"}
                    : new String[] {"2.5", "2.2", "2.1", "2.0", "1.0"};
            mChain = new ArrayList<>();
            for (String version : versions) {
                mChain.add("vendor.qti.hardware.radio.qtiradio@" + version + "::" + type);
            }
            mChain.add(BASE);
            // ABI hashes from the same generated interfaces, in inheritance order.
            mHashes = response ? new String[] {
                "cc4a5fc3d91c3f9d31441d459c29f540be9d702ecef10b2b3fa277feda00dbd0",
                "d775765583dc7054105778574e60412a34f456b162eacdf99d502168881c1274",
                "2d70155a4a2d01acbc8f026167adfa14739447d367a11940005338f1367289a9",
                "f4f379ce04a9347b943057b4c93cf059fe4e350ca6fe82a9670e19f211a758cd",
                "22afff9cee655cdfa02ec528dfd5e6306f7d53bf0661c8d064309c5e2b4304a3",
                "846e558f5c7769652131a85ebc4e58ff5af59b51e7e8908aa7b4036475808199",
                "5b61dd9acb74654dd5203eddfab38e5bc2d7a7ae6478945401f147e56f8274b9",
                "ec7fd79ed02dfa85bc499426adae3ebe23ef0524f3cd6957139324b83b18ca4c"
            } : new String[] {
                "a77d67fd1bfd0c3cfc8b76a7a0377605275c02bc0ff8a76147befa187505d344",
                "c035c9495f024dda86b86b9f0e8bb26bcc8730c25457a759ade5ff0aa474ff6a",
                "4ea2599c8e895156463397e79aa6c0e3c82bca09ed927f45a71052416ca98f5f",
                "00962c1ed449d31f5f4917354d9711142b6713103d9f282adc4a2541d201fbeb",
                "475e9144b8404dea34516a436a02bc79d3e40c990c41d65ba34e7cb72ec2496a",
                "ec7fd79ed02dfa85bc499426adae3ebe23ef0524f3cd6957139324b83b18ca4c"
            };
        }

        @Override
        public IHwBinder asBinder() { return this; }

        @Override
        public IHwInterface queryLocalInterface(String descriptor) {
            return mChain.get(0).equals(descriptor) ? this : null;
        }

        @Override
        public boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie) { return true; }

        @Override
        public boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) { return true; }

        @Override
        public void onTransact(int code, HwParcel request, HwParcel reply, int flags)
                throws RemoteException {
            boolean oneWay = (flags & 1) != 0;
            if (mIsResponse && (code == 17 || code == 18)) {
                if (!oneWay) { reject(reply); return; }
                request.enforceInterface(RESPONSE);
                int serial = request.readInt32();
                int error = request.readInt32();
                int value = request.readInt32();
                if (code == 17) mListener.onSet(serial, error, value);
                else mListener.onQuery(serial, error, value);
                return;
            }
            if (!mIsResponse && code == 10) {
                if (!oneWay) { reject(reply); return; }
                request.enforceInterface(INDICATION);
                mListener.onModeChanged(request.readInt32());
                return;
            }
            boolean baseOneWay = code == INSTRUMENTATION || code == SYSPROPS_CHANGED;
            switch (code) {
                case INTERFACE_CHAIN:
                case DEBUG:
                case INTERFACE_DESCRIPTOR:
                case HASH_CHAIN:
                case INSTRUMENTATION:
                case PING:
                case DEBUG_INFO:
                case SYSPROPS_CHANGED:
                    if (oneWay != baseOneWay) { if (!oneWay) reject(reply); return; }
                    request.enforceInterface(BASE);
                    if (baseOneWay) return;
                    reply.writeStatus(0);
                    if (code == INTERFACE_CHAIN) {
                        reply.writeStringVector(mChain);
                    } else if (code == INTERFACE_DESCRIPTOR) {
                        reply.writeString(mChain.get(0));
                    } else if (code == HASH_CHAIN) {
                        HwBlob header = new HwBlob(16);
                        HwBlob hashes = new HwBlob(32 * mHashes.length);
                        header.putInt32(8, mHashes.length);
                        header.putBool(12, false);
                        for (int i = 0; i < mHashes.length; i++) {
                            for (int j = 0; j < 32; j++) {
                                hashes.putInt8(i * 32L + j,
                                        (byte) Integer.parseInt(mHashes[i].substring(j * 2, j * 2 + 2), 16));
                            }
                        }
                        header.putBlob(0, hashes);
                        reply.writeBuffer(header);
                    } else if (code == DEBUG_INFO) {
                        HwBlob info = new HwBlob(24);
                        info.putInt32(0, Process.myPid());
                        info.putInt64(8, 0);
                        info.putInt32(16, 0);
                        reply.writeBuffer(info);
                    } else if (code == DEBUG) {
                        request.readNativeHandle();
                        request.readStringVector();
                    }
                    reply.send();
                    return;
                default:
                    // We issue no other QTI requests. Unrelated unsolicited
                    // one-way indications must not be mistaken for NR responses.
                    if (!oneWay) reject(reply);
            }
        }

        private static void reject(HwParcel reply) {
            reply.writeStatus(Integer.MIN_VALUE);
            reply.send();
        }
    }
}
