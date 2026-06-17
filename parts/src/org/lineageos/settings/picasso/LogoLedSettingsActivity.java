/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.picasso;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

public final class LogoLedSettingsActivity extends CollapsingToolbarBaseActivity {
    private static final String TAG_LOGO_LED = "logo_led";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.logo_led_title);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new LogoLedSettingsFragment(), TAG_LOGO_LED)
                    .commit();
        }
    }

    public static final class LogoLedSettingsFragment extends PreferenceFragmentCompat
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        private SwitchPreference mLogoLedPreference;
        private SwitchPreference mKeepOnScreenOffPreference;
        private ListPreference mModePreference;
        private ListPreference mSpeedPreference;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            getPreferenceManager().setSharedPreferencesName(LogoLedController.PREFS_NAME);
            addPreferencesFromResource(R.xml.logo_led_settings);

            mLogoLedPreference = findPreference(LogoLedController.KEY_ENABLED);
            mModePreference = findPreference(LogoLedController.KEY_MODE);
            mKeepOnScreenOffPreference = findPreference(LogoLedController.KEY_KEEP_ON_SCREEN_OFF);
            mSpeedPreference = findPreference(LogoLedController.KEY_SPEED);

            mLogoLedPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                LogoLedController.setEnabled(requireContext(), (Boolean) newValue);
                mLogoLedPreference.setChecked((Boolean) newValue);
                return false;
            });

            mModePreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String mode = (String) newValue;
                LogoLedController.setMode(requireContext(), mode);
                mModePreference.setValue(mode);
                updateListPreferenceSummary(mModePreference);
                updateSpeedVisibility(mode);
                return false;
            });

            mKeepOnScreenOffPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                LogoLedController.setKeepOnScreenOff(requireContext(), (Boolean) newValue);
                mKeepOnScreenOffPreference.setChecked((Boolean) newValue);
                return false;
            });

            mSpeedPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String speed = (String) newValue;
                LogoLedController.setSpeed(requireContext(), speed);
                mSpeedPreference.setValue(speed);
                updateListPreferenceSummary(mSpeedPreference);
                return false;
            });

            refreshState();
        }

        @Override
        public void onStart() {
            super.onStart();
            getPreferenceManager().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onStop() {
            getPreferenceManager().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
            super.onStop();
        }

        @Override
        public void onResume() {
            super.onResume();
            requireActivity().setTitle(R.string.logo_led_title);
            refreshState();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (LogoLedController.KEY_ENABLED.equals(key)
                    || LogoLedController.KEY_MODE.equals(key)
                    || LogoLedController.KEY_KEEP_ON_SCREEN_OFF.equals(key)
                    || LogoLedController.KEY_SPEED.equals(key)) {
                refreshState();
            }
        }

        private void updateSpeedVisibility(String mode) {
            mSpeedPreference.setEnabled(LogoLedController.MODE_BREATH.equals(mode));
        }

        private void refreshState() {
            String mode = LogoLedController.getMode(requireContext());
            String speed = LogoLedController.getSpeed(requireContext());

            setSwitchChecked(mLogoLedPreference, LogoLedController.isEnabled(requireContext()));
            setListValue(mModePreference, mode);
            updateListPreferenceSummary(mModePreference);
            setSwitchChecked(mKeepOnScreenOffPreference,
                    LogoLedController.keepOnScreenOff(requireContext()));
            setListValue(mSpeedPreference, speed);
            updateListPreferenceSummary(mSpeedPreference);
            updateSpeedVisibility(mode);
        }

        private static void setSwitchChecked(SwitchPreference preference, boolean checked) {
            if (preference.isChecked() != checked) {
                preference.setChecked(checked);
            }
        }

        private static void setListValue(ListPreference preference, String value) {
            if (!value.equals(preference.getValue())) {
                preference.setValue(value);
            }
        }

        private static void updateListPreferenceSummary(ListPreference preference) {
            preference.setSummary(preference.getEntry());
        }
    }
}
