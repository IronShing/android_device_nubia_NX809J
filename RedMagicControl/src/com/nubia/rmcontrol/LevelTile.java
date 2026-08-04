package com.nubia.rmcontrol;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Multi-level QS tile: each tap cycles Off -> 1 -> 2 -> ... -> max -> Off.
 * The chosen level is written to a persist property (e.g. persist.sys.fan.level);
 * a vendor_init action maps that level to the vendor sysfs node. The tile shows the
 * current level in its subtitle and is ACTIVE for any level > 0.
 */
public abstract class LevelTile extends TileService {

    /** e.g. "persist.sys.fan.level" */
    protected abstract String propKey();
    /** highest level (max); levels are 0..max, 0 = Off */
    protected abstract int maxLevel();
    protected abstract int iconRes();
    protected abstract String baseLabel();
    /** subtitle for a given level (override for custom names) */
    protected String levelName(int level) { return level == 0 ? "Off" : String.valueOf(level); }

    @Override
    public void onStartListening() { super.onStartListening(); refresh(); }

    @Override
    public void onClick() {
        super.onClick();
        int next = (curLevel() + 1) % (maxLevel() + 1);
        Prop.set(propKey(), Integer.toString(next));
        refresh();
    }

    private int curLevel() {
        try { return Math.max(0, Math.min(maxLevel(), Integer.parseInt(Prop.get(propKey(), "0").trim()))); }
        catch (Exception e) { return 0; }
    }

    protected void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        int lvl = curLevel();
        t.setState(lvl > 0 ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        // show the current level right in the label so each tap is unambiguous
        t.setLabel(lvl > 0 ? baseLabel() + " · " + levelName(lvl) : baseLabel());
        try { t.setSubtitle(levelName(lvl)); } catch (Throwable ignore) {}
        try { t.setIcon(Icon.createWithResource(this, iconRes())); } catch (Throwable ignore) {}
        t.updateTile();
    }
}
