package com.nubia.rmcontrol;
public class FpWakeTile extends PropTile {
    protected String propKey() { return "persist.sys.fp_wake.enabled"; }
    protected int iconRes() { return R.drawable.ic_fingerprint; }
    protected String label() { return getString(R.string.tile_fpwake); }
}
