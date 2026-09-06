package com.nubia.rmcontrol;

import android.util.Log;

/**
 * Qualcomm Game Post Processing (GPP): AI super-resolution and frame interpolation
 * for games, run on the Hexagon NPU through the vendor HexLP service. This is what
 * stock RedMagic OS sells as the "R4 gaming chip" super-resolution / super-frame.
 *
 * Plumbing: libgui's QtiSurfaceExtensionGPP (frameworks/native, nx809j-gpp) swaps a
 * game's EGL SurfaceView producer for one served by /system/bin/gppservice, which
 * renders the frames through libgpphexlpsession -> HexLP. Both sides read the
 * vendor.gpp.* properties below; none of them persist across a reboot, so the user's
 * choice is kept in persist.sys.rm.gpp.* and pushed out again from RmApp at boot.
 * The property protocol is the one stock services.jar (MindSyncService.dumpGfrc)
 * speaks, so the blobs see exactly what they see on stock.
 *
 * vendor.gpp.frc.enable: 0x22 = dynamic on, 0x21 = dynamic off (libgui polls it on
 * every dequeue, so a change applies to a game that is already running). The other
 * values are gppservice-side knobs. gppservice only touches packages listed in
 * /system/etc/gpp_app_list unless allgame is set, and it can process only one
 * surface at a time.
 */
final class GamePostProcessing {
    private static final String TAG = "RMControl.GPP";

    static final String PROP_ENABLED = "persist.sys.rm.gpp";
    static final String PROP_ALLGAME = "persist.sys.rm.gpp.allgame";
    static final String PROP_INTERP  = "persist.sys.rm.gpp.interp";
    static final String PROP_UPSCALE = "persist.sys.rm.gpp.upscale";   // 0 off, 1, 2

    static final boolean DEF_ENABLED = false;
    static final boolean DEF_ALLGAME = false;
    static final boolean DEF_INTERP  = true;
    static final int     DEF_UPSCALE = 1;

    /** Push the persisted choice out to the live vendor.gpp.* properties. */
    static void apply() {
        boolean on = Prop.getBool(PROP_ENABLED, DEF_ENABLED);
        if (on) {
            boolean allgame = Prop.getBool(PROP_ALLGAME, DEF_ALLGAME);
            boolean interp  = Prop.getBool(PROP_INTERP, DEF_INTERP);
            int upscale = clamp(parse(Prop.get(PROP_UPSCALE, Integer.toString(DEF_UPSCALE)),
                    DEF_UPSCALE), 0, 2);
            // Same order MindSyncService uses: knobs first, then the enable that libgui polls.
            Prop.set("vendor.gpp.allgame.enable", allgame ? "1" : "0");
            Prop.set("vendor.gpp.gfrc.interp.rate", interp ? "1" : "0");
            Prop.set("vendor.gpp.gfrc.upscale.ratio", Integer.toString(upscale));
            Prop.set("vendor.gpp.dynamic.settings.enable", "1");
            Prop.set("vendor.gpp.frc.enable", "0x22");
            Log.i(TAG, "on: allgame=" + allgame + " interp=" + interp + " upscale=" + upscale);
        } else {
            Prop.set("vendor.gpp.frc.enable", "0x21");
            Prop.set("vendor.gpp.dynamic.settings.enable", "0");
            Prop.set("vendor.gpp.allgame.enable", "0");
            Log.i(TAG, "off");
        }
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private GamePostProcessing() {}
}
