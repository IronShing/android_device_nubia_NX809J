package com.nubia.rmcontrol;
public class LoudnessTile extends PropTile {
    protected String propKey() { return "persist.sys.loudness.enabled"; }
    protected int iconRes() { return R.drawable.ic_loudness; }
    protected String label() { return getString(R.string.tile_loudness); }
}
