package com.nubia.rmcontrol;
public class CoolingTile extends LevelTile {
    protected String propKey() { return "persist.sys.cooling.level"; }
    protected int maxLevel() { return 1; }               // Off / On — piezo micropump is on/off (stock liquid_cooling_off_on)
    protected int iconRes() { return R.drawable.ic_cooling; }
    protected String baseLabel() { return getString(R.string.tile_cooling); }
    protected String levelName(int l) { return l == 0 ? getString(R.string.off) : getString(R.string.on); }
}
