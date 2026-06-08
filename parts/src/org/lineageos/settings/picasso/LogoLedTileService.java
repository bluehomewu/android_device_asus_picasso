/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.picasso;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class LogoLedTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        LogoLedController.setEnabled(this, !LogoLedController.isEnabled(this));
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        boolean enabled = LogoLedController.isEnabled(this);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_snapdragon_logo_light));
        tile.setLabel(getString(R.string.logo_led_tile_label));
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
