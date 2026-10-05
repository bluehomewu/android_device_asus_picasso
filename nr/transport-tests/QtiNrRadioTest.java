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
import android.os.RemoteException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Tests the actual production transport against independently verified wire facts. */
public final class QtiNrRadioTest {
    private static final String PREFIX = "vendor.qti.hardware.radio.qtiradio@";
    private static final String BASE = "android.hidl.base@1.0::IBase";
    private static final String RADIO = PREFIX + "2.5::IQtiRadio";
    private static final String RESPONSE = PREFIX + "2.5::IQtiRadioResponse";
    private static final String INDICATION = PREFIX + "2.5::IQtiRadioIndication";
    private static final String[] RESPONSE_VERSIONS =
            {"2.5", "2.4", "2.3", "2.2", "2.1", "2.0", "1.0"};
    private static final String[] INDICATION_VERSIONS = {"2.5", "2.2", "2.1", "2.0", "1.0"};
    private static final String[] RESPONSE_HASHES = {
        "cc4a5fc3d91c3f9d31441d459c29f540be9d702ecef10b2b3fa277feda00dbd0",
        "d775765583dc7054105778574e60412a34f456b162eacdf99d502168881c1274",
        "2d70155a4a2d01acbc8f026167adfa14739447d367a11940005338f1367289a9",
        "f4f379ce04a9347b943057b4c93cf059fe4e350ca6fe82a9670e19f211a758cd",
        "22afff9cee655cdfa02ec528dfd5e6306f7d53bf0661c8d064309c5e2b4304a3",
        "846e558f5c7769652131a85ebc4e58ff5af59b51e7e8908aa7b4036475808199",
        "5b61dd9acb74654dd5203eddfab38e5bc2d7a7ae6478945401f147e56f8274b9",
        "ec7fd79ed02dfa85bc499426adae3ebe23ef0524f3cd6957139324b83b18ca4c"
    };
    private static final String[] INDICATION_HASHES = {
        "a77d67fd1bfd0c3cfc8b76a7a0377605275c02bc0ff8a76147befa187505d344",
        "c035c9495f024dda86b86b9f0e8bb26bcc8730c25457a759ade5ff0aa474ff6a",
        "4ea2599c8e895156463397e79aa6c0e3c82bca09ed927f45a71052416ca98f5f",
        "00962c1ed449d31f5f4917354d9711142b6713103d9f282adc4a2541d201fbeb",
        "475e9144b8404dea34516a436a02bc79d3e40c990c41d65ba34e7cb72ec2496a",
        "ec7fd79ed02dfa85bc499426adae3ebe23ef0524f3cd6957139324b83b18ca4c"
    };
    private static int sChecks;

    private static void equal(Object expected, Object actual, String what) {
        sChecks++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
        }
    }

    private interface Action { void run() throws Exception; }

    private static void expect(Class<? extends Throwable> type, Action action) throws Exception {
        sChecks++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getName(), failure);
        }
        throw new AssertionError("Expected " + type.getName() + ", no exception thrown");
    }

    private static final class Listener implements QtiNrRadio.Listener {
        final List<String> events = new ArrayList<>();
        @Override public void onSet(int serial, int error, int status) {
            events.add("set:" + serial + ":" + error + ":" + status);
        }
        @Override public void onQuery(int serial, int error, int mode) {
            events.add("query:" + serial + ":" + error + ":" + mode);
        }
        @Override public void onModeChanged(int mode) { events.add("changed:" + mode); }
        @Override public void onDeath() { events.add("death"); }
    }

    private static final class Call {
        final int code;
        final int flags;
        final HwParcel request;
        final HwParcel reply;
        Call(int code, HwParcel request, HwParcel reply, int flags) {
            this.code = code;
            this.request = request;
            this.reply = reply;
            this.flags = flags;
        }
    }

    private static final class Remote implements IHwBinder {
        final List<Call> calls = new ArrayList<>();
        boolean supported = true;
        boolean linkSucceeds = true;
        int chainStatus;
        int callbackStatus;
        int failCode = -1;
        boolean runtimeFailure;
        int links;
        int unlinks;
        DeathRecipient death;
        IHwBinder response;
        IHwBinder indication;

        @Override
        public void transact(int code, HwParcel request, HwParcel reply, int flags)
                throws RemoteException {
            calls.add(new Call(code, request, reply, flags));
            if (code == failCode) {
                if (runtimeFailure) throw new IllegalStateException("Injected transaction failure");
                throw new RemoteException("Injected transaction failure");
            }
            if (code == 256067662) {
                equal(BASE, request.token, "Service chain token");
                equal(0, flags, "Service chain is synchronous");
                equal(0, request.payload.size(), "Service chain has no arguments");
                reply.writeStatus(chainStatus);
                reply.writeStringVector(new ArrayList<>(supported
                        ? Arrays.asList(RADIO, BASE) : Arrays.asList(BASE)));
            } else if (code == 1) {
                equal(PREFIX + "1.0::IQtiRadio", request.token, "Registration uses base version");
                equal(0, flags, "Registration is synchronous");
                response = request.readStrongBinder();
                indication = request.readStrongBinder();
                equal(0, request.unreadCount(), "Registration has exactly two binders");
                reply.writeStatus(callbackStatus);
            }
            // Oneway request replies intentionally have no success status.
        }

        @Override public IHwInterface queryLocalInterface(String descriptor) { return null; }
        @Override public boolean linkToDeath(DeathRecipient recipient, long cookie) {
            links++;
            equal(0L, cookie, "Death cookie");
            if (linkSucceeds) death = recipient;
            return linkSucceeds;
        }
        @Override public boolean unlinkToDeath(DeathRecipient recipient) {
            unlinks++;
            if (death != recipient) return false;
            death = null;
            return true;
        }

        void checkReleased() {
            for (Call call : calls) {
                equal(true, call.request.temporaryReleased, "Request temporary storage released");
                equal(true, call.reply.released, "Reply released");
            }
        }
    }

    private static Remote install() {
        Remote remote = new Remote();
        HwBinder.SERVICES.clear();
        HwBinder.SERVICES.put("slot1", remote);
        HwBinder.SERVICES.put("slot2", remote);
        HwBinder.unavailableThrows = false;
        return remote;
    }

    private static HwParcel callback(IHwBinder binder, int code, String token, int flags,
            int... values) throws RemoteException {
        HwParcel request = new HwParcel();
        request.writeInterfaceToken(token);
        for (int value : values) request.writeInt32(value);
        HwParcel reply = new HwParcel();
        binder.transact(code, request, reply, flags);
        return reply;
    }

    private static void requestsAndLifecycle() throws Exception {
        for (int slot = 0; slot <= 1; slot++) {
            Remote remote = install();
            Listener listener = new Listener();
            QtiNrRadio radio = QtiNrRadio.connect(slot, listener);
            equal(RADIO, HwBinder.lastInterface, "Lookup exact version");
            equal("slot" + (slot + 1), HwBinder.lastInstance, "Logical slot mapping");
            equal(false, HwBinder.lastRetry, "Lookup must not wait indefinitely");
            equal(1, remote.links, "One death monitor");
            equal(2, remote.calls.size(), "Chain check then callback registration");
            equal(1, remote.calls.get(0).reply.verifyCalls, "Chain status checked");
            equal(1, remote.calls.get(1).reply.verifyCalls, "Registration status checked");
            for (int mode = 0; mode <= 2; mode++) {
                int serial = 103 + mode;
                radio.set(serial, mode);
                Call call = remote.calls.get(remote.calls.size() - 1);
                equal(20, call.code, "setNrConfig code");
                equal(RADIO, call.request.token, "setNrConfig token");
                equal(1, call.flags, "setNrConfig oneway");
                equal(Arrays.asList(serial, mode), call.request.payload, "setNrConfig arguments");
                equal(0, call.reply.verifyCalls, "No synchronous oneway status read");
            }
            radio.query(Integer.MAX_VALUE);
            Call query = remote.calls.get(remote.calls.size() - 1);
            equal(21, query.code, "queryNrConfig code");
            equal(RADIO, query.request.token, "queryNrConfig token");
            equal(1, query.flags, "queryNrConfig oneway");
            equal(Arrays.asList(Integer.MAX_VALUE), query.request.payload, "Query serial preserved");
            int calls = remote.calls.size();
            expect(IllegalArgumentException.class, () -> radio.set(1, -1));
            expect(IllegalArgumentException.class, () -> radio.set(1, 3));
            equal(calls, remote.calls.size(), "Invalid modes never reach remote");

            remote.death.serviceDied(0);
            equal(Arrays.asList("death"), listener.events, "Death event reaches owner");
            radio.close();
            equal(1, remote.unlinks, "Close removes death monitor");
            equal(null, remote.death, "Death monitor not retained");
            equal(calls, remote.calls.size(), "Close does not steal replacement callbacks");
            remote.checkReleased();
        }
        int lookups = HwBinder.lookups;
        expect(IllegalArgumentException.class, () -> QtiNrRadio.connect(-1, new Listener()));
        expect(IllegalArgumentException.class, () -> QtiNrRadio.connect(2, new Listener()));
        equal(lookups, HwBinder.lookups, "Invalid slots never look up a service");
    }

    private static void failures() throws Exception {
        HwBinder.SERVICES.clear();
        expect(RemoteException.class, () -> QtiNrRadio.connect(0, new Listener()));
        HwBinder.unavailableThrows = true;
        expect(NoSuchElementException.class, () -> QtiNrRadio.connect(0, new Listener()));
        for (int scenario = 0; scenario < 7; scenario++) {
            Remote remote = install();
            switch (scenario) {
                case 0: remote.supported = false; break;
                case 1: remote.chainStatus = -1; break;
                case 2: remote.failCode = 256067662; break;
                case 3: remote.linkSucceeds = false; break;
                case 4: remote.callbackStatus = -1; break;
                case 5: remote.failCode = 1; break;
                case 6: remote.failCode = 1; remote.runtimeFailure = true; break;
                default: throw new AssertionError();
            }
            expect(scenario == 6 ? IllegalStateException.class : RemoteException.class,
                    () -> QtiNrRadio.connect(0, new Listener()));
            equal(scenario >= 4 ? 1 : 0, remote.unlinks, "Failed registration unlinks death");
            equal(null, remote.death, "Failed construction leaves no death monitor");
            if (scenario <= 2) equal(0, remote.links, "Unsupported transport never registers");
            remote.checkReleased();
        }
        for (boolean runtime : new boolean[] {false, true}) {
            Remote remote = install();
            QtiNrRadio radio = QtiNrRadio.connect(0, new Listener());
            remote.failCode = 20;
            remote.runtimeFailure = runtime;
            expect(runtime ? IllegalStateException.class : RemoteException.class,
                    () -> radio.set(12, 2));
            remote.failCode = 21;
            expect(runtime ? IllegalStateException.class : RemoteException.class,
                    () -> radio.query(13));
            radio.close();
            remote.checkReleased();
        }
    }

    private static void responses() throws Exception {
        Remote remote = install();
        Listener listener = new Listener();
        QtiNrRadio radio = QtiNrRadio.connect(0, listener);
        equal(0, callback(remote.response, 17, RESPONSE, 1, 11, 42, 1).sendCalls,
                "Set response has no synchronous reply");
        callback(remote.response, 17, RESPONSE, 1, 12, 0, 0);
        callback(remote.response, 18, RESPONSE, 1, 13, 7, 2);
        callback(remote.indication, 10, INDICATION, 1, 1);
        callback(remote.indication, 10, INDICATION, 1, -1);
        equal(Arrays.asList("set:11:42:1", "set:12:0:0", "query:13:7:2",
                "changed:1", "changed:-1"), listener.events,
                "Serial/error/status/config kept separate; invalid modem values not forged");
        int events = listener.events.size();
        for (int code : new int[] {17, 18}) {
            HwParcel reply = callback(remote.response, code, RESPONSE, 0, 1, 0, 1);
            equal(Integer.MIN_VALUE, reply.status, "Wrong callback flags rejected");
            equal(1, reply.sendCalls, "Wrong synchronous callback gets an error reply");
            expect(SecurityException.class,
                    () -> callback(remote.response, code, INDICATION, 1, 1, 0, 1));
        }
        equal(Integer.MIN_VALUE,
                callback(remote.indication, 10, INDICATION, 0, 1).status,
                "Wrong indication flags rejected");
        expect(SecurityException.class,
                () -> callback(remote.indication, 10, RESPONSE, 1, 1));
        expect(IndexOutOfBoundsException.class,
                () -> callback(remote.response, 18, RESPONSE, 1, 1, 0));
        callback(remote.response, 10, INDICATION, 1, 1);
        callback(remote.indication, 17, RESPONSE, 1, 1, 0, 1);
        callback(remote.indication, 9, PREFIX + "2.2::IQtiRadioIndication", 1, 1);
        equal(events, listener.events.size(), "Malformed/unrelated callbacks never notify owner");
        equal(Integer.MIN_VALUE, callback(remote.response, 999, BASE, 0).status,
                "Unsupported synchronous transaction rejected");
        equal(0, callback(remote.response, 999, BASE, 1).sendCalls,
                "Unsupported oneway transaction has no reply");
        radio.close();
    }

    private static void baseProtocol() throws Exception {
        Remote remote = install();
        QtiNrRadio radio = QtiNrRadio.connect(0, new Listener());
        for (boolean response : new boolean[] {true, false}) {
            IHwBinder binder = response ? remote.response : remote.indication;
            String descriptor = response ? RESPONSE : INDICATION;
            List<String> expected = new ArrayList<>();
            for (String version : response ? RESPONSE_VERSIONS : INDICATION_VERSIONS) {
                expected.add(PREFIX + version + "::IQtiRadio"
                        + (response ? "Response" : "Indication"));
            }
            expected.add(BASE);
            equal(binder, binder.queryLocalInterface(descriptor).asBinder(), "Exact local interface");
            equal(null, binder.queryLocalInterface(BASE), "No false local cast to ancestor");
            HwParcel chain = callback(binder, 256067662, BASE, 0);
            equal(0, chain.status, "Chain reply status");
            equal(1, chain.sendCalls, "Chain reply sent");
            equal(expected, chain.readStringVector(), "Complete callback interface chain");
            equal(descriptor, callback(binder, 256136003, BASE, 0).readString(), "Descriptor");
            HwBlob header = (HwBlob) callback(binder, 256398152, BASE, 0).payload.get(0);
            String[] hashes = response ? RESPONSE_HASHES : INDICATION_HASHES;
            equal(16, header.bytes.length, "Hash vector header size");
            equal(hashes.length, header.getInt32(8), "Hash vector size field");
            equal((byte) 0, header.bytes[12], "Hash vector owns-buffer marker");
            HwBlob child = header.children.get(0L);
            equal(32 * hashes.length, child.bytes.length, "Hash array allocation");
            for (int i = 0; i < hashes.length; i++) {
                equal(hashes[i], hex(Arrays.copyOfRange(child.bytes, i * 32, (i + 1) * 32)),
                        "Hash matches original firmware at index " + i);
            }
            HwBlob info = (HwBlob) callback(binder, 257049926, BASE, 0).payload.get(0);
            equal(24, info.bytes.length, "DebugInfo size");
            equal(12345, info.getInt32(0), "DebugInfo pid");
            equal(0L, info.getInt64(8), "DebugInfo pointer");
            equal(0, info.getInt32(16), "DebugInfo architecture unknown");
            equal(0, callback(binder, 256921159, BASE, 0).status, "Ping success");
            HwParcel debugRequest = new HwParcel();
            debugRequest.writeInterfaceToken(BASE);
            debugRequest.writeNativeHandle(null);
            debugRequest.writeStringVector(new ArrayList<>(Arrays.asList("test")));
            HwParcel debugReply = new HwParcel();
            binder.transact(256131655, debugRequest, debugReply, 0);
            equal(0, debugRequest.unreadCount(), "Debug arguments consumed");
            equal(1, debugReply.sendCalls, "Debug gets reply");
            for (int code : new int[] {256462420, 257120595}) {
                equal(0, callback(binder, code, BASE, 1).sendCalls, "Base oneway has no reply");
                equal(Integer.MIN_VALUE, callback(binder, code, BASE, 0).status,
                        "Base oneway rejects synchronous call");
            }
            for (int code : new int[] {256067662, 256131655, 256136003, 256398152,
                    256921159, 257049926}) {
                equal(0, callback(binder, code, BASE, 1).sendCalls,
                        "Incorrect oneway base call produces no reply");
                expect(SecurityException.class, () -> callback(binder, code, RADIO, 0));
            }
        }
        radio.close();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static int u32(byte[] bytes, int offset) {
        return u16(bytes, offset) | (u16(bytes, offset + 2) << 16);
    }

    private static String dexString(byte[] dex, int index) {
        if (index < 0 || index >= u32(dex, 0x38)) throw new AssertionError("Bad string index");
        int offset = u32(dex, u32(dex, 0x3c) + 4 * index);
        int lengthBytes = 0;
        while ((dex[offset++] & 0x80) != 0) {
            if (++lengthBytes == 5) throw new AssertionError("Bad string length");
        }
        int end = offset;
        while (dex[end] != 0) end++;
        return new String(dex, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static int dexConst(byte[] dex, int offset) {
        int opcode = dex[offset] & 0xff;
        if (opcode == 0x12) return dex[offset + 1] >> 4; // const/4
        if (opcode == 0x13) return (short) u16(dex, offset + 2); // const/16
        throw new AssertionError("Expected constant instruction");
    }

    private static void stockCall(byte[] dex, int tokenOffset, int txOffset, int flagsOffset,
            String token, int tx, int flags, int codeOffset, int intWrites, int binderWrites) {
        equal(0x1a, dex[tokenOffset] & 0xff, "Original token is const-string");
        equal(token, dexString(dex, u16(dex, tokenOffset + 2)), "Original token text");
        equal(tx, dexConst(dex, txOffset), "Original transaction constant");
        equal(flags, dexConst(dex, flagsOffset), "Original flags constant");
        int countInts = 0;
        int countBinders = 0;
        int end = codeOffset + 16 + u32(dex, codeOffset + 12) * 2;
        for (int offset = codeOffset + 16; offset + 6 <= end; offset += 2) {
            // These method IDs are also independently checked below before use.
            if (u16(dex, offset) == 0x206e && u16(dex, offset + 2) == 0x0d08) countInts++;
            if (u16(dex, offset) == 0x206e && u16(dex, offset + 2) == 0x0d11) countBinders++;
        }
        equal(intWrites, countInts, "Original int32 argument count");
        equal(binderWrites, countBinders, "Original strong binder argument count");
    }

    private static void verifyStock(Path jar) throws Exception {
        final int limit = 2 * 1024 * 1024;
        if (Files.size(jar) > limit) throw new AssertionError("Unexpected stock JAR size");
        equal("eb7800519c145eaf4f69948753a83d74fb0e402cda6170763adf20dfdda01911",
                hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))),
                "Pinned ASUS 18.1055.2307.269 JAR provenance");
        byte[] dex;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("classes.dex");
            if (entry == null || entry.getSize() > limit) throw new AssertionError("Invalid DEX entry");
            try (InputStream stream = zip.getInputStream(entry)) {
                dex = stream.readNBytes(limit + 1);
            }
        }
        equal(1018520, dex.length, "Original classes.dex size");
        String[][] sets = {RESPONSE_HASHES, INDICATION_HASHES};
        int[] starts = {0x09d3d0, 0x09bebc};
        for (int set = 0; set < sets.length; set++) {
            for (int i = 0; i < sets[set].length; i++) {
                int offset = starts[set] + 40 * i;
                equal(0x0300, u16(dex, offset), "DEX array payload identifier");
                equal(1, u16(dex, offset + 2), "DEX array element width");
                equal(32, u32(dex, offset + 4), "DEX hash array element count");
                equal(sets[set][i], hex(Arrays.copyOfRange(dex, offset + 8, offset + 40)),
                        "Expected hash independently read from original bytecode");
            }
        }
        int methodTable = u32(dex, 0x5c);
        for (int method : new int[] {0x0d08, 0x0d11}) {
            equal(method == 0x0d08 ? "writeInt32" : "writeStrongBinder",
                    dexString(dex, u32(dex, methodTable + 8 * method + 4)),
                    "Original parcel writer method identifier");
            int classType = u16(dex, methodTable + 8 * method);
            equal("Landroid/os/HwParcel;",
                    dexString(dex, u32(dex, u32(dex, 0x44) + 4 * classType)),
                    "Original parcel writer class");
        }
        stockCall(dex, 0x09a9a6, 0x09a9ea, 0x09a9ec,
                PREFIX + "1.0::IQtiRadio", 1, 0, 0x09a98c, 0, 2);
        stockCall(dex, 0x09ab12, 0x09ab36, 0x09ab3a,
                RADIO, 20, 1, 0x09aaf8, 2, 0);
        stockCall(dex, 0x09a716, 0x09a734, 0x09a738,
                RADIO, 21, 1, 0x09a6fc, 1, 0);
        stockCall(dex, 0x09d1d2, 0x09d1fc, 0x09d200,
                RESPONSE, 17, 1, 0x09d1b8, 3, 0);
        stockCall(dex, 0x09cdce, 0x09cdf8, 0x09cdfc,
                RESPONSE, 18, 1, 0x09cdb4, 3, 0);
        stockCall(dex, 0x09ba0a, 0x09ba28, 0x09ba2c,
                INDICATION, 10, 1, 0x09b9f0, 1, 0);
        System.out.println("Original stock DEX contract audit: PASS");
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Expected optional stock JAR path");
        requestsAndLifecycle();
        failures();
        responses();
        baseProtocol();
        if (args.length == 1) verifyStock(Path.of(args[0]));
        System.out.println("QtiNrRadio host transport tests: PASS (" + sChecks + " checks)");
    }
}
