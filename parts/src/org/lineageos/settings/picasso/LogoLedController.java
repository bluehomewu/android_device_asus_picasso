/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.picasso;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class LogoLedController {
    static final String PREFS_NAME = "logo_led";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_MODE = "mode";
    static final String KEY_KEEP_ON_SCREEN_OFF = "keep_on_screen_off";
    static final String KEY_SPEED = "speed";

    static final String MODE_STATIC = "static";
    static final String MODE_BREATH = "breath";

    static final String SPEED_SLOW = "slow";
    static final String SPEED_NORMAL = "normal";
    static final String SPEED_FAST = "fast";

    private static final String TAG = "PicassoLogoLed";
    private static final String LOGO_LED_BASE_PATH = "/sys/class/leds/aura_sync";
    private static final String APPLY_PATH = LOGO_LED_BASE_PATH + "/apply";
    private static final String LED_ON_PATH = LOGO_LED_BASE_PATH + "/led_on";
    private static final String MODE2_PATH = LOGO_LED_BASE_PATH + "/mode2";
    private static final String SPEED_PATH = LOGO_LED_BASE_PATH + "/speed";
    private static final String VDD_PATH = LOGO_LED_BASE_PATH + "/VDD";
    private static final int BOOT_APPLY_ATTEMPTS = 6;
    private static final long BOOT_APPLY_RETRY_DELAY_MS = 2000;

    private LogoLedController() {
    }

    static boolean isEnabled(Context context) {
        return getPreferences(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        getPreferences(context).edit().putBoolean(KEY_ENABLED, enabled).commit();
        writeState(context, enabled, false);
        updateTile(context);
    }

    static String getMode(Context context) {
        return getPreferences(context).getString(KEY_MODE, MODE_STATIC);
    }

    static void setMode(Context context, String mode) {
        getPreferences(context).edit().putString(KEY_MODE, mode).apply();
        applySavedState(context);
    }

    static boolean keepOnScreenOff(Context context) {
        return getPreferences(context).getBoolean(KEY_KEEP_ON_SCREEN_OFF, true);
    }

    static void setKeepOnScreenOff(Context context, boolean keepOnScreenOff) {
        getPreferences(context).edit().putBoolean(KEY_KEEP_ON_SCREEN_OFF, keepOnScreenOff).apply();
        applySavedState(context);
    }

    static String getSpeed(Context context) {
        return getPreferences(context).getString(KEY_SPEED, SPEED_NORMAL);
    }

    static void setSpeed(Context context, String speed) {
        getPreferences(context).edit().putString(KEY_SPEED, speed).apply();
        applySavedState(context);
    }

    static void applySavedState(Context context) {
        writeState(context, isEnabled(context), false);
    }

    static void applySavedStateOnBoot(Context context) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            for (int attempt = 0; attempt < BOOT_APPLY_ATTEMPTS; attempt++) {
                if (writeState(appContext, isEnabled(appContext), false)) {
                    return;
                }
                sleepQuietly(BOOT_APPLY_RETRY_DELAY_MS);
            }
        }, TAG + "-BootApply").start();
    }

    static void handleScreenOff(Context context) {
        if (isEnabled(context) && !keepOnScreenOff(context)) {
            writeState(context, false, true);
        }
    }

    static void handleScreenOn(Context context) {
        applySavedState(context);
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void updateTile(Context context) {
        TileService.requestListeningState(context,
                new ComponentName(context, LogoLedTileService.class));
    }

    private static boolean writeState(Context context, boolean enabled, boolean screenOffOnly) {
        boolean success = true;
        if (enabled) {
            success &= writeNode(VDD_PATH, "1");
            sleepQuietly(1000);
            success &= writeNode(LED_ON_PATH, "1");
            sleepQuietly(300);
            if (MODE_BREATH.equals(getMode(context))) {
                success &= writeNode(SPEED_PATH, getSpeedValue(context));
            }
            success &= writeNode(MODE2_PATH, getModeValue(context));
            readNode(MODE2_PATH);
            sleepQuietly(100);
            success &= writeNode(APPLY_PATH, "1");
        } else {
            success &= writeNode(LED_ON_PATH, "0");
            if (!screenOffOnly) {
                success &= writeNode(MODE2_PATH, "0");
                success &= writeNode(APPLY_PATH, "1");
                success &= writeNode(VDD_PATH, "0");
            }
        }
        return success;
    }

    private static String getModeValue(Context context) {
        return MODE_BREATH.equals(getMode(context)) ? "3, FFFFFF, FFFFFF" : "2, FFFFFF, FFFFFF";
    }

    private static String getSpeedValue(Context context) {
        switch (getSpeed(context)) {
            case SPEED_SLOW:
                return "2";
            case SPEED_FAST:
                return "254";
            case SPEED_NORMAL:
            default:
                return "0";
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void readNode(String path) {
        File node = new File(path);
        if (!node.exists()) {
            Log.w(TAG, path + " is not available");
            return;
        }

        try (FileInputStream stream = new FileInputStream(node)) {
            byte[] buffer = new byte[64];
            stream.read(buffer);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read " + path, e);
        }
    }

    private static boolean writeNode(String path, String value) {
        File node = new File(path);
        if (!node.exists()) {
            Log.w(TAG, path + " is not available");
            return false;
        }

        try (FileOutputStream stream = new FileOutputStream(node)) {
            stream.write(value.getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + path, e);
            return false;
        }
    }
}
