/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.picasso;

import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

public final class LogoLedSettingsActivity extends PreferenceActivity {
    private ListPreference mSpeedPreference;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(this);

        SwitchPreference logoLed = new SwitchPreference(this);
        logoLed.setKey(LogoLedController.KEY_ENABLED);
        logoLed.setTitle(R.string.logo_led_title);
        logoLed.setChecked(LogoLedController.isEnabled(this));
        logoLed.setOnPreferenceChangeListener((preference, newValue) -> {
            LogoLedController.setEnabled(this, (Boolean) newValue);
            return true;
        });

        ListPreference logoMode = new ListPreference(this);
        logoMode.setKey(LogoLedController.KEY_MODE);
        logoMode.setTitle(R.string.logo_led_mode_title);
        logoMode.setEntries(R.array.logo_led_mode_entries);
        logoMode.setEntryValues(R.array.logo_led_mode_values);
        logoMode.setValue(LogoLedController.getMode(this));
        logoMode.setSummary(logoMode.getEntry());
        logoMode.setOnPreferenceChangeListener((preference, newValue) -> {
            String mode = (String) newValue;
            LogoLedController.setMode(this, mode);
            logoMode.setValue(mode);
            logoMode.setSummary(logoMode.getEntry());
            updateSpeedVisibility(mode);
            return false;
        });

        SwitchPreference keepOnScreenOff = new SwitchPreference(this);
        keepOnScreenOff.setKey(LogoLedController.KEY_KEEP_ON_SCREEN_OFF);
        keepOnScreenOff.setTitle(R.string.logo_led_keep_on_screen_off_title);
        keepOnScreenOff.setChecked(LogoLedController.keepOnScreenOff(this));
        keepOnScreenOff.setOnPreferenceChangeListener((preference, newValue) -> {
            LogoLedController.setKeepOnScreenOff(this, (Boolean) newValue);
            return true;
        });

        mSpeedPreference = new ListPreference(this);
        mSpeedPreference.setKey(LogoLedController.KEY_SPEED);
        mSpeedPreference.setTitle(R.string.logo_led_speed_title);
        mSpeedPreference.setEntries(R.array.logo_led_speed_entries);
        mSpeedPreference.setEntryValues(R.array.logo_led_speed_values);
        mSpeedPreference.setValue(LogoLedController.getSpeed(this));
        mSpeedPreference.setSummary(mSpeedPreference.getEntry());
        mSpeedPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            String speed = (String) newValue;
            LogoLedController.setSpeed(this, speed);
            mSpeedPreference.setValue(speed);
            mSpeedPreference.setSummary(mSpeedPreference.getEntry());
            return false;
        });

        screen.addPreference(logoLed);
        screen.addPreference(logoMode);
        screen.addPreference(keepOnScreenOff);
        screen.addPreference(mSpeedPreference);
        setPreferenceScreen(screen);
        updateSpeedVisibility(LogoLedController.getMode(this));
    }

    private void updateSpeedVisibility(String mode) {
        mSpeedPreference.setEnabled(LogoLedController.MODE_BREATH.equals(mode));
    }
}
