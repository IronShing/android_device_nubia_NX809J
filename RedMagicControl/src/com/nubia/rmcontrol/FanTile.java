package com.nubia.rmcontrol;

import android.service.quicksettings.Tile;

/**
 * Cooling-fan tile: Off -> Auto -> 1 -> ... -> 5 -> Off, the same choices as the "Cooling fan"
 * row in Settings. Auto is persist.sys.rm.fan_auto=1 (hwcontrol drives the level from the
 * temperature curve); any number clears fan_auto first, otherwise the curve overwrites the
 * level within seconds and the tap looks ignored (XDA #235: "set 5 in the panel ... back to
 * auto" while Fan speed while gaming was Auto).
 */
public class FanTile extends LevelTile {
    static final String AUTO_KEY = "persist.sys.rm.fan_auto";

    protected String propKey() { return "persist.sys.fan.level"; }
    protected int maxLevel() { return 5; }               // Off + 5 speeds
    protected int iconRes() { return R.drawable.ic_fan; }
    protected String baseLabel() { return getString(R.string.tile_fan); }
    protected String levelName(int l) { return l == 0 ? getString(R.string.off) : "Speed " + l; }

    private boolean isAuto() { return Prop.getBool(AUTO_KEY, true); }

    @Override
    public void onClick() {
        // Not super.onClick(): the cycle has an extra Auto step and must clear fan_auto.
        final int lvl = isAuto() ? -1 : curLevel();
        if (lvl == 0) {                       // Off -> Auto
            Prop.set(AUTO_KEY, "1");
        } else if (lvl < 0) {                 // Auto -> 1
            Prop.set(AUTO_KEY, "0");
            Prop.set(propKey(), "1");
        } else {                              // n -> n+1, 5 -> Off
            Prop.set(AUTO_KEY, "0");
            Prop.set(propKey(), Integer.toString(lvl >= maxLevel() ? 0 : lvl + 1));
        }
        refresh();
    }

    @Override
    protected void refresh() {
        if (!isAuto()) { super.refresh(); return; }
        Tile t = getQsTile();
        if (t == null) return;
        final int lvl = curLevel();          // what the curve is running right now
        t.setState(Tile.STATE_ACTIVE);
        t.setLabel(baseLabel() + " \u00b7 Auto" + (lvl > 0 ? " (" + lvl + ")" : ""));
        try { t.setSubtitle("Auto"); } catch (Throwable ignore) {}
        try { t.setIcon(android.graphics.drawable.Icon.createWithResource(this, iconRes())); } catch (Throwable ignore) {}
        t.updateTile();
    }
}
