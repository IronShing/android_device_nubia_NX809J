package com.nubia.rmcontrol;
public class FanTile extends LevelTile {
    protected String propKey() { return "persist.sys.fan.level"; }
    protected int maxLevel() { return 5; }               // Off + 5 speeds
    protected int iconRes() { return R.drawable.ic_fan; }
    protected String baseLabel() { return getString(R.string.tile_fan); }
    protected String levelName(int l) { return l == 0 ? getString(R.string.off) : "Speed " + l; }
}
