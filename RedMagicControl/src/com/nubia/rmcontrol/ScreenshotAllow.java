package com.nubia.rmcontrol;

import android.content.Context;
import android.provider.Settings;

import java.util.TreeSet;

/**
 * Per-app "ignore FLAG_SECURE" list. The framework (WindowManagerService / WindowState
 * .isSecureLocked, SurfaceView.setSecure) reads {@link #SETTING} — comma-separated package
 * names — and never marks those apps' surfaces secure, so screenshots, screen recording and
 * recents thumbnails work for them. Server-side: the app still sees its own flag set. Device
 * admin (work profile) and sensitive-notification protection are untouched.
 */
final class ScreenshotAllow {
    static final String SETTING = "rm_screenshot_allow";

    private ScreenshotAllow() {}

    static TreeSet<String> list(Context ctx) {
        final TreeSet<String> out = new TreeSet<>();
        final String raw = Settings.Secure.getString(ctx.getContentResolver(), SETTING);
        if (raw == null) return out;
        for (String p : raw.split(",")) {
            p = p.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    static void add(Context ctx, String pkg) {
        final TreeSet<String> s = list(ctx);
        if (s.add(pkg)) write(ctx, s);
    }

    static void remove(Context ctx, String pkg) {
        final TreeSet<String> s = list(ctx);
        if (s.remove(pkg)) write(ctx, s);
    }

    private static void write(Context ctx, TreeSet<String> s) {
        Settings.Secure.putString(ctx.getContentResolver(), SETTING, String.join(",", s));
    }
}
