/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.picasso;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ScreenStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            LogoLedController.handleScreenOff(context);
        } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
            LogoLedController.handleScreenOn(context);
        }
    }
}
