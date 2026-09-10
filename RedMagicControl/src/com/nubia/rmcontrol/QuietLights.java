package com.nubia.rmcontrol;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * Do Not Disturb == lights out. While DND (any interruption filter other than "all") is active
 * and the user has left the switch on, every RGB zone -- logo, shoulder strip and the fan ring,
 * including the "fan ring follows the auto fan" glow -- stays dark, so a fan spin-up at 3 am does
 * not light the nightstand.
 *
 * The app only publishes ONE derived property, persist.sys.rm.lights_quiet (1/0); the actual
 * LED handling is in hwlevels/redmagic_hw_arm.rc, whose LED triggers are all gated on
 * lights_quiet=0 and which blanks every zone on lights_quiet=1 (init can chain property
 * conditions but not evaluate them, hence the derived prop rather than the two inputs). When
 * DND ends the trigger flips back to 0 and init replays the user's own zone values, so nothing
 * is stored on our side and nothing can drift.
 */
final class QuietLights {
    private static final String TAG = "RMControl";

    /** User switch (Lighting tab); default on. */
    static final String PROP_ENABLED = "persist.sys.rm.dnd_lights_off";
    /** Derived: 1 while DND is active AND the switch is on. Consumed by redmagic_hw_arm.rc. */
    static final String PROP_QUIET = "persist.sys.rm.lights_quiet";

    private QuietLights() {}

    static void start(Context ctx) {
        final Context app = ctx.getApplicationContext();
        // Protected system broadcast; NotificationManagerService sends it on every filter change
        // (manual DND, schedules, Bedtime mode).
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                sync(c);
            }
        }, new IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED));
        // Persisted value may be stale after a reboot (DND ended while we were not running, or
        // the very first boot with this build, where init seeded it to 0).
        sync(app);
    }

    /** Recompute lights_quiet from the live DND state and the switch; write only on change. */
    static void sync(Context ctx) {
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        final int filter = nm == null ? NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                                      : nm.getCurrentInterruptionFilter();
        final boolean dnd = filter != NotificationManager.INTERRUPTION_FILTER_ALL
                && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        final boolean quiet = dnd && Prop.getBool(PROP_ENABLED, true);
        final String want = quiet ? "1" : "0";
        if (want.equals(Prop.get(PROP_QUIET, ""))) return;
        Log.i(TAG, "lights_quiet -> " + want + " (interruption filter " + filter + ")");
        Prop.set(PROP_QUIET, want);
    }
}
