package com.nubia.rmcontrol;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * Ships "Fast" as the animation-speed default.
 *
 * The three animation scales live in Settings.Global, whose defaults come from
 * SettingsProvider's own resources — we cannot change those without patching
 * frameworks/base, which a repo sync would wipe. So this applies the intended default
 * once, on the first boot that finds the marker unset, and then never touches the
 * setting again: whatever the user later picks in Display -> Animation speed (or in
 * Developer options) is theirs and survives every subsequent boot.
 *
 * Value matches the "Fast (like stock)" entry in SettingsActivity — stock RedMagic runs
 * its transitions faster than AOSP's default, which is much of why it feels quicker.
 */
final class AnimationDefaults {

    private static final String TAG = "RMControl";
    /** Set once the shipped default has been applied; never cleared. */
    private static final String MARKER = "persist.sys.rm.anim_default_done";
    /** Same as scales[1] / "Fast (like stock)" in SettingsActivity. */
    private static final float FAST = 0.75f;

    private AnimationDefaults() {}

    static void applyOnce(final Context ctx) {
        if (Prop.getBool(MARKER, false)) return;

        // Off the main thread and off Application.onCreate: a persistent app starts before the
        // settings provider is necessarily serving, and a failed write here must not cost the
        // marker -- otherwise the default would be silently skipped for good.
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Settings.Global.putFloat(ctx.getContentResolver(),
                            Settings.Global.WINDOW_ANIMATION_SCALE, FAST);
                    Settings.Global.putFloat(ctx.getContentResolver(),
                            Settings.Global.TRANSITION_ANIMATION_SCALE, FAST);
                    Settings.Global.putFloat(ctx.getContentResolver(),
                            Settings.Global.ANIMATOR_DURATION_SCALE, FAST);
                    Prop.set(MARKER, "1");
                    Log.i(TAG, "AnimationDefaults: applied Fast (" + FAST + ") as the shipped default");
                } catch (Throwable t) {
                    // Marker deliberately left unset so the next boot retries.
                    Log.e(TAG, "AnimationDefaults: could not apply default; will retry next boot", t);
                }
            }
        }, "anim-default").start();
    }
}
