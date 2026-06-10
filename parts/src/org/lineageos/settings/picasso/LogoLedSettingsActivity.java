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
    private SwitchPreference mLogoLedPreference;
    private SwitchPreference mKeepOnScreenOffPreference;
    private ListPreference mModePreference;
    private ListPreference mSpeedPreference;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(this);

        mLogoLedPreference = new SwitchPreference(this);
        mLogoLedPreference.setKey(LogoLedController.KEY_ENABLED);
        mLogoLedPreference.setPersistent(false);
        mLogoLedPreference.setTitle(R.string.logo_led_title);
        mLogoLedPreference.setChecked(LogoLedController.isEnabled(this));
        mLogoLedPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            LogoLedController.setEnabled(this, (Boolean) newValue);
            mLogoLedPreference.setChecked((Boolean) newValue);
            return false;
        });

        mModePreference = new ListPreference(this);
        mModePreference.setKey(LogoLedController.KEY_MODE);
        mModePreference.setPersistent(false);
        mModePreference.setTitle(R.string.logo_led_mode_title);
        mModePreference.setEntries(R.array.logo_led_mode_entries);
        mModePreference.setEntryValues(R.array.logo_led_mode_values);
        mModePreference.setValue(LogoLedController.getMode(this));
        mModePreference.setSummary(mModePreference.getEntry());
        mModePreference.setOnPreferenceChangeListener((preference, newValue) -> {
            String mode = (String) newValue;
            LogoLedController.setMode(this, mode);
            mModePreference.setValue(mode);
            mModePreference.setSummary(mModePreference.getEntry());
            updateSpeedVisibility(mode);
            return false;
        });

        mKeepOnScreenOffPreference = new SwitchPreference(this);
        mKeepOnScreenOffPreference.setKey(LogoLedController.KEY_KEEP_ON_SCREEN_OFF);
        mKeepOnScreenOffPreference.setPersistent(false);
        mKeepOnScreenOffPreference.setTitle(R.string.logo_led_keep_on_screen_off_title);
        mKeepOnScreenOffPreference.setChecked(LogoLedController.keepOnScreenOff(this));
        mKeepOnScreenOffPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            LogoLedController.setKeepOnScreenOff(this, (Boolean) newValue);
            mKeepOnScreenOffPreference.setChecked((Boolean) newValue);
            return false;
        });

        mSpeedPreference = new ListPreference(this);
        mSpeedPreference.setKey(LogoLedController.KEY_SPEED);
        mSpeedPreference.setPersistent(false);
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

        screen.addPreference(mLogoLedPreference);
        screen.addPreference(mModePreference);
        screen.addPreference(mKeepOnScreenOffPreference);
        screen.addPreference(mSpeedPreference);
        setPreferenceScreen(screen);
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private void updateSpeedVisibility(String mode) {
        mSpeedPreference.setEnabled(LogoLedController.MODE_BREATH.equals(mode));
    }

    private void refreshState() {
        String mode = LogoLedController.getMode(this);

        mLogoLedPreference.setChecked(LogoLedController.isEnabled(this));
        mModePreference.setValue(mode);
        mModePreference.setSummary(mModePreference.getEntry());
        mKeepOnScreenOffPreference.setChecked(LogoLedController.keepOnScreenOff(this));
        mSpeedPreference.setValue(LogoLedController.getSpeed(this));
        mSpeedPreference.setSummary(mSpeedPreference.getEntry());
        updateSpeedVisibility(mode);
    }
}
