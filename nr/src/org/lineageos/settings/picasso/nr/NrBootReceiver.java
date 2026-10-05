/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.picasso.nr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class NrBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        NrManager manager = ((NrApplication) context.getApplicationContext()).manager();
        if (manager != null) manager.refresh();
    }
}
