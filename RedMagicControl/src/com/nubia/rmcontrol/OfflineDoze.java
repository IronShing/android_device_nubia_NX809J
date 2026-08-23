package com.nubia.rmcontrol;

import android.content.Context;
import android.net.ConnectivityManager;
import android.provider.Settings;
import android.util.Log;

/**
 * "Sleep harder when offline" — stretches the Doze windows while the device has no
 * network at all, and restores stock behaviour the moment one appears.
 *
 * Why this is safe only when gated on connectivity: stretching the maintenance windows
 * delays push, sync and anything else riding Doze maintenance. With no network there is
 * nothing to deliver, so the cost is ~zero. The gate is deliberately "no default network"
 * and NOT airplane_mode_on: airplane mode + WiFi is a normal setup that DOES deliver push,
 * and must keep stock timings.
 *
 * What still fires while stretched: clock alarms and timers. AlarmManagerService tags
 * setAlarmClock() alarms with FLAG_WAKE_FROM_IDLE, which pulls the device out of idle
 * regardless of these constants. setExactAndAllowWhileIdle reminders also fire on their
 * own schedule rather than waiting for a maintenance window.
 *
 * Expect a small win. The wake this addresses (68 pm8xxx_rtc_alarm, ~every 10 min) costs
 * roughly 393 ms of CPU per wake; measured discharge was 0.00-0.14 %/hr either way. This
 * mostly stops the phone waking for work it provably cannot do.
 */
final class OfflineDoze {

    private static final String TAG = "RMControl";

    /** Settings.Global key parsed by DeviceIdleController.Constants. Applies with no reboot. */
    private static final String SETTING = "device_idle_constants";

    static final String KEY_ENABLED = "persist.sys.rm.doze_offline";
    /**
     * Off by default — opt-in. The saving is small (see the class comment), so it is the
     * user's call, not something to switch on behind their back.
     */
    static final boolean DEF_ENABLED = false;

    /** Set while our override is live, so we only ever clear a value we wrote ourselves. */
    private static final String KEY_APPLIED = "persist.sys.rm.doze_applied";

    // User-tunable windows, in minutes (see SettingsActivity -> Battery).
    static final String KEY_LIGHT_IDLE   = "persist.sys.rm.doze_light_idle_min";
    static final String KEY_LIGHT_MAX    = "persist.sys.rm.doze_light_max_min";
    static final String KEY_IDLE_PENDING = "persist.sys.rm.doze_idle_pending_min";
    static final String KEY_MAX_PENDING  = "persist.sys.rm.doze_max_pending_min";

    // Stock AOSP for reference: light_idle_to=5, light_max_idle_to=30,
    // idle_pending_to=5, max_idle_pending_to=10.
    static final int DEF_LIGHT_IDLE   = 15;
    static final int DEF_LIGHT_MAX    = 60;
    static final int DEF_IDLE_PENDING = 15;
    static final int DEF_MAX_PENDING  = 30;

    static boolean isOffline(Context ctx) {
        final ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        return cm == null || cm.getActiveNetwork() == null;
    }

    /** Re-evaluate connectivity and apply. Call after any settings change. */
    static void reapply(Context ctx) {
        apply(ctx, isOffline(ctx));
    }

    static void apply(Context ctx, boolean offline) {
        final boolean stretch = offline && Prop.getBool(KEY_ENABLED, DEF_ENABLED);

        // Only ever touch the setting if we are the ones who set it. Without this the
        // feature would write an empty override on every boot and network change even when
        // switched off, clobbering any device_idle_constants set by hand. The marker is a
        // persist prop so it survives a reboot and cleanup stays exact.
        if (!stretch && !Prop.getBool(KEY_APPLIED, false)) return;

        // Empty string (not null) restores stock: DeviceIdleController re-parses and every
        // constant falls back to its default. Verified on-device; avoids depending on
        // SettingsProvider's null-means-delete behaviour.
        final String value = stretch ? build() : "";
        try {
            Settings.Global.putString(ctx.getContentResolver(), SETTING, value);
            Prop.set(KEY_APPLIED, stretch ? "1" : "0");
            Log.i(TAG, "offline-doze " + (stretch ? "applied " + value : "cleared (network present)"));
        } catch (Throwable t) {
            Log.e(TAG, "could not write " + SETTING, t);
        }
    }

    private static String build() {
        return "light_idle_to=" + ms(KEY_LIGHT_IDLE, DEF_LIGHT_IDLE)
                + ",light_max_idle_to=" + ms(KEY_LIGHT_MAX, DEF_LIGHT_MAX)
                + ",idle_pending_to=" + ms(KEY_IDLE_PENDING, DEF_IDLE_PENDING)
                + ",max_idle_pending_to=" + ms(KEY_MAX_PENDING, DEF_MAX_PENDING);
    }

    static int minutes(String key, int def) {
        try {
            return Integer.parseInt(Prop.get(key, Integer.toString(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static long ms(String key, int def) {
        return minutes(key, def) * 60000L;
    }

    private OfflineDoze() {}
}
