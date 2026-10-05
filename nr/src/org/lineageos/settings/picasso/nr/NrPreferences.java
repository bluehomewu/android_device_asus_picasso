/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.picasso.nr;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Durable per-subscription preferences, independent of Android's mutable preference cache.
 * A failed SharedPreferences commit can still change that cache. Consequently, only the
 * initial snapshot and successfully committed replacements are ever used for reads.
 */
final class NrPreferences {
    interface Backing {
        Map<String, ?> readAll();

        /** Replace the complete backing state; true means the snapshot is durable. */
        boolean commit(Map<String, Object> values);
    }

    private static final String MODE_PREFIX = "mode_";
    private static final String MIGRATED = "legacy_migrated";

    private final Backing mBacking;
    private Map<String, Object> mConfirmed;

    NrPreferences(Backing backing) {
        mBacking = Objects.requireNonNull(backing);
        Map<String, ?> initial = backing.readAll();
        if (initial == null) throw invalidStoredState();
        mConfirmed = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : initial.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (MIGRATED.equals(key)) {
                if (!(value instanceof Boolean)) throw invalidStoredState();
            } else {
                if (key == null || !key.startsWith(MODE_PREFIX)
                        || !(value instanceof Integer) || !validMode((Integer) value)) {
                    throw invalidStoredState();
                }
                try {
                    int subId = Integer.parseInt(key.substring(MODE_PREFIX.length()));
                    if (subId < 0 || !key.equals(key(subId))) throw invalidStoredState();
                } catch (NumberFormatException invalid) {
                    throw invalidStoredState();
                }
            }
            mConfirmed.put(key, value);
        }
    }

    synchronized int readMode(int subId, int defaultMode) {
        checkSubId(subId);
        checkMode(defaultMode);
        Object value = mConfirmed.get(key(subId));
        return value == null ? defaultMode : (Integer) value;
    }

    synchronized boolean writeMode(int subId, int mode) {
        checkSubId(subId);
        checkMode(mode);
        Map<String, Object> replacement = new LinkedHashMap<>(mConfirmed);
        replacement.put(key(subId), mode);
        return commit(replacement);
    }

    /**
     * Import the legacy choice for SIMs present at migration, preserving saved choices.
     * Empty SIM lists never mark migration complete. Later SIMs use their own default.
     */
    synchronized boolean migrateLegacy(List<Integer> activeSubIds, int legacy) {
        Objects.requireNonNull(activeSubIds);
        if (legacy < -1 || legacy > 2) {
            throw new IllegalArgumentException("Invalid legacy NR mode");
        }
        Set<Integer> unique = new HashSet<>();
        for (Integer subId : activeSubIds) {
            if (subId == null) throw new IllegalArgumentException("Invalid subscription");
            checkSubId(subId);
            if (!unique.add(subId)) throw new IllegalArgumentException("Duplicate subscription");
        }
        if (activeSubIds.isEmpty()) return false;
        if (Boolean.TRUE.equals(mConfirmed.get(MIGRATED))) return true;
        Map<String, Object> replacement = new LinkedHashMap<>(mConfirmed);
        if (legacy >= 0) {
            for (int subId : activeSubIds) replacement.putIfAbsent(key(subId), legacy);
        }
        replacement.put(MIGRATED, true);
        return commit(replacement);
    }

    private boolean commit(Map<String, Object> replacement) {
        // Never hand the backing implementation the confirmed map itself.
        if (!mBacking.commit(new LinkedHashMap<>(replacement))) return false;
        mConfirmed = replacement;
        return true;
    }

    private static String key(int subId) { return MODE_PREFIX + subId; }
    private static boolean validMode(int mode) { return mode >= 0 && mode <= 2; }

    private static void checkSubId(int subId) {
        if (subId < 0) throw new IllegalArgumentException("Invalid subscription");
    }

    private static void checkMode(int mode) {
        if (!validMode(mode)) throw new IllegalArgumentException("Invalid NR mode");
    }

    private static IllegalStateException invalidStoredState() {
        // Do not include preference keys or values, which contain subscription IDs.
        return new IllegalStateException("Invalid stored NR preferences");
    }
}
