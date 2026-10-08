package com.nubia.rmcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Persistent logcat to /data/misc/logd (logcatd). Off by default since 2026-10-04 (XDA #307): it
 * kept rotating ~128 MB of logs on storage on every phone. Driven through logd's own properties
 * (system/logging/logcat/logcatd.rc): on = persist.logd.logpersistd=logcatd, off =
 * logd.logpersistd=clear (stops logcatd, deletes its files, clears the persist prop).
 */
final class LogPersist {
    private static final String TAG = "RmLogPersist";
    private static final String PERSIST = "persist.logd.logpersistd";
    private static final String KEY_CLEANED = "logpersist_cleaned_v1";

    static boolean enabled() {
        return "logcatd".equals(Prop.get(PERSIST, ""));
    }

    static void set(boolean on) {
        if (on) {
            Prop.set("persist.logd.logpersistd.size", "64");
            Prop.set(PERSIST, "logcatd");
        } else {
            Prop.set("logd.logpersistd", "clear");
        }
        Log.i(TAG, "persistent logcat " + (on ? "on" : "off (files cleared)"));
    }

    /** Wipe the stored on-storage logs now; re-arm logging if it was on (#318, NX123Dos). */
    static void clearStored() {
        final boolean on = enabled();
        Prop.set("logd.logpersistd", "clear");   // stops logcatd, deletes its files, clears the prop
        if (on) set(true);                        // keep persisting from now, just without the old files
        Log.i(TAG, "stored logs cleared" + (on ? " (re-armed)" : ""));
    }

    /**
     * Once after updating from a build that had it on by default: the files in /data/misc/logd
     * stay behind when the build.prop default goes away, so clear them unless the user has
     * switched persistence on themselves.
     */
    static void cleanupOnce(Context ctx) {
        final SharedPreferences p = ctx.getSharedPreferences("rmcontrol", Context.MODE_PRIVATE);
        if (p.getBoolean(KEY_CLEANED, false)) return;
        if (!enabled()) {
            Prop.set("logd.logpersistd", "clear");
            Log.i(TAG, "cleared persistent logs left by an earlier build");
        }
        p.edit().putBoolean(KEY_CLEANED, true).apply();
    }

    private LogPersist() {}
}
