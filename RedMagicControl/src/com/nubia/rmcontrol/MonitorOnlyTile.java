package com.nubia.rmcontrol;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.view.Display;

/** QS tile: phone screen off, external display stays on (MonitorOnlyService). */
public class MonitorOnlyTile extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (TouchpadActivity.externalDisplayId(this) == Display.INVALID_DISPLAY) { refresh(); return; }
        startService(new Intent(this, MonitorOnlyService.class));
    }

    private void refresh() {
        final Tile t = getQsTile();
        if (t == null) return;
        final boolean have = TouchpadActivity.externalDisplayId(this) != Display.INVALID_DISPLAY;
        t.setState(have ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        t.setLabel(getString(R.string.tile_monitor_only));
        t.setSubtitle(have ? "Phone screen off" : "No external screen");
        try { t.setIcon(Icon.createWithResource(this, R.drawable.ic_monitor_only)); } catch (Throwable ignore) {}
        t.updateTile();
    }
}
