package com.nx809j.voiprecorder;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

/** Shared helpers used by both detectors (persistent FGS and NotificationListener). */
final class Util {
    private Util() {}

    /** Most-recent foregrounded package — identifies the VoIP caller. */
    static String foregroundApp(Context ctx) {
        try {
            UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            UsageEvents ev = usm.queryEvents(now - 15_000, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                        || e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    last = e.getPackageName();
                }
            }
            return last;
        } catch (Exception ex) {
            return null;
        }
    }

    /** True if the foregrounded app should be recorded per the user's allow-list. */
    static boolean shouldRecord(Prefs prefs, String pkg) {
        return prefs.recordAllVoip() || (pkg != null && prefs.allowlist().contains(pkg));
    }
}
