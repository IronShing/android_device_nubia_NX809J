package com.nubia.rmcontrol;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Base QS tile that toggles a boolean persist system property. */
public abstract class PropTile extends TileService {

    /** e.g. "persist.sys.fp_wake.enabled" */
    protected abstract String propKey();
    protected abstract int iconRes();
    protected abstract String label();

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean now = Prop.getBool(propKey(), false);
        Prop.set(propKey(), now ? "0" : "1");
        refresh();
    }

    protected void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = Prop.getBool(propKey(), false);
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(label());
        try { t.setIcon(Icon.createWithResource(this, iconRes())); } catch (Throwable ignore) {}
        t.updateTile();
    }
}
