package com.nubia.rmcontrol;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** QS tile: master switch for the per-app camera / mic / location guard. */
public class PrivacyTile extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        PrivacyGuard.setEnabled(this, !PrivacyGuard.enabled());
        refresh();
    }

    private void refresh() {
        final Tile t = getQsTile();
        if (t == null) return;
        final int n = PrivacyGuard.rules(this).size();
        t.setState(PrivacyGuard.enabled() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(getString(R.string.tile_privacy));
        t.setSubtitle(n == 0 ? "No apps" : n + (n == 1 ? " app" : " apps"));
        try { t.setIcon(Icon.createWithResource(this, R.drawable.ic_privacy)); } catch (Throwable ignore) {}
        t.updateTile();
    }
}
