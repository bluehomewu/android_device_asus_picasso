/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.picasso.nr;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Host tests including SharedPreferences' memory mutation on failed disk writes. */
public final class NrPreferencesTest {
    private static int sChecks;

    private static final class MemoryBacking implements NrPreferences.Backing {
        final Map<String, Object> memory = new LinkedHashMap<>();
        final Map<String, Object> durable = new LinkedHashMap<>();
        int reads;
        int commits;
        int failedWrites;
        boolean failNext;
        boolean throwNext;

        MemoryBacking(Map<String, Object> initial) {
            memory.putAll(initial);
            durable.putAll(initial);
        }

        @Override public Map<String, ?> readAll() {
            reads++;
            return memory;
        }

        @Override public boolean commit(Map<String, Object> replacement) {
            commits++;
            memory.clear();
            memory.putAll(replacement);
            if (throwNext) {
                throwNext = false;
                failedWrites++;
                throw new IllegalStateException("Simulated backing failure");
            }
            if (failNext) {
                failNext = false;
                failedWrites++;
                return false;
            }
            durable.clear();
            durable.putAll(replacement);
            return true;
        }
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static void check(boolean condition, String description) {
        sChecks++;
        if (!condition) throw new AssertionError(description);
    }

    private static void throwsType(Class<? extends RuntimeException> expected, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            check(expected.isInstance(failure), "Wrong failure type");
            return;
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private static void failedModeWrite() {
        MemoryBacking backing = new MemoryBacking(values("mode_11", 1));
        NrPreferences prefs = new NrPreferences(backing);
        backing.failNext = true;
        check(!prefs.writeMode(11, 2), "Failed write reported success");
        check(backing.memory.get("mode_11").equals(2), "Test must poison backing memory");
        check(backing.durable.get("mode_11").equals(1), "Failed write changed disk");
        check(prefs.readMode(11, 0) == 1, "Failed write became visible");
        // Removal and reinsertion cause fresh controller reads, not a new preferences object.
        check(prefs.readMode(22, 0) == 0, "New SIM inherited another SIM's mode");
        check(prefs.readMode(11, 0) == 1, "Reinserted SIM read failed cached write");
        check(prefs.writeMode(22, 2), "Independent successful write failed");
        check(backing.durable.equals(values("mode_11", 1, "mode_22", 2)),
                "Full replacement preserved poisoned backing memory");
        check(prefs.writeMode(11, 2), "Retry did not succeed");
        check(prefs.readMode(11, 0) == 2, "Successful retry not visible");
        check(backing.failedWrites == 1 && backing.reads == 1, "Backing cache reread");
    }

    private static void failedMigration() {
        MemoryBacking backing = new MemoryBacking(values("mode_11", 2));
        NrPreferences prefs = new NrPreferences(backing);
        backing.failNext = true;
        check(!prefs.migrateLegacy(Arrays.asList(11, 22), 1), "Migration failure reported success");
        check(Boolean.TRUE.equals(backing.memory.get("legacy_migrated")),
                "Test must poison migration flag");
        check(!backing.durable.containsKey("legacy_migrated"), "Failed migration reached disk");
        check(prefs.readMode(11, 0) == 2, "Migration replaced an existing preference");
        check(prefs.readMode(22, 0) == 0, "Failed migration became readable");
        // Retry with a different active set cannot preserve the failed attempt's SIM record.
        check(prefs.migrateLegacy(Arrays.asList(11, 33), 1), "Migration retry failed");
        check(backing.commits == 2, "Poisoned flag skipped required durable retry");
        check(backing.durable.equals(values("mode_11", 2, "mode_33", 1,
                        "legacy_migrated", true)), "Retry used uncommitted state");
        check(prefs.readMode(22, 0) == 0, "Later SIM did not get independent default");
        check(prefs.migrateLegacy(Collections.singletonList(22), 2), "Confirmed migration lost");
        check(backing.commits == 2 && prefs.readMode(22, 0) == 0,
                "Already completed migration was repeated");
    }

    private static void failuresAndSnapshots() {
        MemoryBacking backing = new MemoryBacking(values("mode_11", 1));
        NrPreferences prefs = new NrPreferences(backing);
        backing.memory.put("mode_11", 2);
        backing.memory.put("mode_22", 2);
        check(prefs.readMode(11, 0) == 1 && prefs.readMode(22, 0) == 0,
                "Confirmed map aliases backing memory");
        backing.throwNext = true;
        throwsType(IllegalStateException.class, () -> prefs.writeMode(11, 0));
        check(prefs.readMode(11, 0) == 1, "Thrown write advanced confirmed state");
        check(prefs.writeMode(11, 1), "Same-value durable retry failed");
        check(backing.durable.equals(values("mode_11", 1)), "Retry failed to replace full snapshot");
        NrPreferences reopened = new NrPreferences(new MemoryBacking(backing.durable));
        check(reopened.readMode(11, 0) == 1, "Reboot did not preserve durable preference");
    }

    private static void migrationEdges() {
        MemoryBacking backing = new MemoryBacking(Collections.emptyMap());
        NrPreferences prefs = new NrPreferences(backing);
        check(!prefs.migrateLegacy(Collections.emptyList(), 1), "No-SIM boot migrated");
        check(backing.commits == 0, "No-SIM boot wrote preferences");
        check(prefs.migrateLegacy(Collections.singletonList(11), -1), "Neutral legacy not accepted");
        check(backing.durable.equals(values("legacy_migrated", true)),
                "Neutral legacy created a SIM preference");
        check(prefs.readMode(11, 0) == 0, "Neutral legacy changed default");
        for (int legacy = 0; legacy <= 2; legacy++) {
            MemoryBacking candidate = new MemoryBacking(values("legacy_migrated", false));
            NrPreferences store = new NrPreferences(candidate);
            check(store.migrateLegacy(Arrays.asList(11, 22), legacy), "Valid legacy mode rejected");
            check(store.readMode(11, 0) == legacy && store.readMode(22, 0) == legacy,
                    "Migration did not copy legacy to both current SIMs");
        }
    }

    private static void validation() {
        String[] invalidKeys = {"unknown", "mode_", "mode_-1", "mode_+1", "mode_01",
                "mode_2147483648", "mode_1x", null};
        for (String key : invalidKeys) {
            throwsType(IllegalStateException.class,
                    () -> new NrPreferences(new MemoryBacking(values(key, 1))));
        }
        Object[] invalidModes = {-1, 3, 1L, "1", true, null};
        for (Object mode : invalidModes) {
            throwsType(IllegalStateException.class,
                    () -> new NrPreferences(new MemoryBacking(values("mode_11", mode))));
        }
        throwsType(IllegalStateException.class,
                () -> new NrPreferences(new MemoryBacking(values("legacy_migrated", 1))));
        MemoryBacking backing = new MemoryBacking(Collections.emptyMap());
        NrPreferences prefs = new NrPreferences(backing);
        throwsType(IllegalArgumentException.class, () -> prefs.readMode(-1, 0));
        throwsType(IllegalArgumentException.class, () -> prefs.readMode(11, 3));
        throwsType(IllegalArgumentException.class, () -> prefs.writeMode(11, -1));
        throwsType(IllegalArgumentException.class, () -> prefs.writeMode(-1, 0));
        throwsType(IllegalArgumentException.class,
                () -> prefs.migrateLegacy(Arrays.asList(11, 11), 0));
        throwsType(IllegalArgumentException.class,
                () -> prefs.migrateLegacy(Collections.singletonList(-1), 0));
        throwsType(IllegalArgumentException.class,
                () -> prefs.migrateLegacy(Collections.singletonList(null), 0));
        throwsType(IllegalArgumentException.class,
                () -> prefs.migrateLegacy(Collections.singletonList(11), -2));
        check(backing.commits == 0, "Invalid input reached backing store");
    }

    public static void main(String[] args) {
        failedModeWrite();
        failedMigration();
        failuresAndSnapshots();
        migrationEdges();
        validation();
        System.out.println("NrPreferences: " + sChecks + " checks passed");
    }
}
