/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.picasso.nr;

import android.app.Application;
import android.os.UserHandle;

public final class NrApplication extends Application {
    private volatile NrManager mManager;

    @Override
    public void onCreate() {
        super.onCreate();
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) {
            mManager = new NrManager(this);
        }
    }

    NrManager manager() { return mManager; }
}
