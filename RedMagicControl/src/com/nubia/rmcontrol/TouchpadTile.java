package com.nubia.rmcontrol;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.view.Display;

/** QS tile: one tap into touchpad mode for the external display (greyed out without one). */
public class TouchpadTile extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (TouchpadActivity.externalDisplayId(this) == Display.INVALID_DISPLAY) { refresh(); return; }
        final Intent i = new Intent(this, TouchpadActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startActivityAndCollapse(pi);
    }

    private void refresh() {
        final Tile t = getQsTile();
        if (t == null) return;
        final boolean have = TouchpadActivity.externalDisplayId(this) != Display.INVALID_DISPLAY;
        t.setState(have ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        t.setLabel(getString(R.string.tile_touchpad));
        t.setSubtitle(have ? "Tap to start" : "No external screen");
        try { t.setIcon(Icon.createWithResource(this, R.drawable.ic_touchpad)); } catch (Throwable ignore) {}
        t.updateTile();
    }
}
