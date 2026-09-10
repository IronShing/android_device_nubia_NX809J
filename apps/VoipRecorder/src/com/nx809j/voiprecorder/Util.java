package com.nx809j.voiprecorder;

import android.content.Context;
import android.content.pm.PackageManager;

/** Shared helpers. */
final class Util {
    private Util() {}

    enum Policy { RECORD, IGNORE, ASK }

    /** What to do with a VoIP app that just started a call, per the user's lists and mode. */
    static Policy policyFor(Prefs prefs, String pkg) {
        if (pkg == null) return Policy.IGNORE;
        if (prefs.denylist().contains(pkg)) return Policy.IGNORE;
        if (prefs.allowlist().contains(pkg)) return Policy.RECORD;
        switch (prefs.mode()) {
            case Prefs.MODE_ALL: return Policy.RECORD;
            case Prefs.MODE_ASK: return Policy.ASK;
            default: return Policy.IGNORE;
        }
    }

    /** Launcher label of pkg, or the package name when it is not installed. */
    static String appLabel(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (l != null && l.length() > 0) return l.toString();
        } catch (Exception ignored) { }
        return pkg;
    }

    /** Strip anything a FAT/ext4 file name or our own parser would choke on. */
    static String fileSafe(String s) {
        if (s == null) return "";
        String out = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return out.length() > 60 ? out.substring(0, 60).trim() : out;
    }
}
