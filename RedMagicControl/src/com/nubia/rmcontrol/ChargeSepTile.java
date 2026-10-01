package com.nubia.rmcontrol;

/** QS tile for charge separation: manual switch, effective state is ChargeSeparation's. */
public class ChargeSepTile extends PropTile {
    protected String propKey() { return ChargeSeparation.PROP_MANUAL; }
    protected int iconRes() { return R.drawable.ic_chargesep; }
    protected String label() { return getString(R.string.tile_chargesep); }

    @Override
    public void onClick() {
        super.onClick();
        ChargeSeparation.reevaluate();
    }
}
