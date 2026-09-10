package com.nx809j.voiprecorder;

import android.content.Context;
import android.provider.Settings;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * App settings. The user-facing switches live in RedMagic Control (Privacy tab), which writes
 * them to Settings.Secure so this engine can read them without any IPC of its own: RM Control
 * shows the consent text before it ever sets {@link #SECURE_ENABLED}, so "enabled" implies
 * consent. RecorderApp observes the keys and re-syncs the call detector on change.
 *
 * Per-app policy: an app is either on the record list, on the ignore list, or unknown; what
 * happens to an unknown app is {@link #SECURE_MODE}. The lists are Secure strings too, so RM
 * Control edits them and the "Record calls from X?" notification here can answer into them.
 */
final class Prefs {
    /** 1 = auto-record on (consent given in RM Control). Written by RM Control only. */
    static final String SECURE_ENABLED = "rm_voiprec_enabled";
    /** Unknown-app policy: {@link #MODE_LISTED}, {@link #MODE_ASK} (default), {@link #MODE_ALL}. */
    static final String SECURE_MODE = "rm_voiprec_mode";
    /** Comma-separated package lists: always record / never record. */
    static final String SECURE_ALLOW = "rm_voiprec_allow";
    static final String SECURE_DENY = "rm_voiprec_deny";
    /** Legacy switch (pre-09-10): 1 meant "record every VoIP app". Ignored now. */
    static final String SECURE_ALL_VOIP = "rm_voiprec_all_voip";

    static final int MODE_LISTED = 0;   // only the record list
    static final int MODE_ASK = 1;      // record list + ask once per unknown app
    static final int MODE_ALL = 2;      // everything except the ignore list

    // WhatsApp (consumer + business) by default; architecture supports any VoIP app.
    static final String DEFAULT_ALLOW = "com.whatsapp,com.whatsapp.w4b";

    private final Context ctx;

    Prefs(Context c) { ctx = c.getApplicationContext(); }

    private boolean secure(String key) {
        return Settings.Secure.getInt(ctx.getContentResolver(), key, 0) != 0;
    }

    boolean isEnabled()   { return secure(SECURE_ENABLED); }
    boolean hasConsent()  { return secure(SECURE_ENABLED); }
    int mode() { return Settings.Secure.getInt(ctx.getContentResolver(), SECURE_MODE, MODE_ASK); }

    Set<String> allowlist() { return list(SECURE_ALLOW, DEFAULT_ALLOW); }
    Set<String> denylist()  { return list(SECURE_DENY, ""); }

    private Set<String> list(String key, String def) {
        String s = Settings.Secure.getString(ctx.getContentResolver(), key);
        if (s == null) s = def;
        Set<String> out = new LinkedHashSet<>();
        for (String p : s.split(",")) { p = p.trim(); if (!p.isEmpty()) out.add(p); }
        return out;
    }

    /** Move pkg onto the record list (allow=true) or the ignore list (allow=false). */
    void setDecision(String pkg, boolean allow) {
        Set<String> a = allowlist(), d = denylist();
        if (allow) { a.add(pkg); d.remove(pkg); } else { d.add(pkg); a.remove(pkg); }
        Settings.Secure.putString(ctx.getContentResolver(), SECURE_ALLOW, String.join(",", a));
        Settings.Secure.putString(ctx.getContentResolver(), SECURE_DENY, String.join(",", d));
    }
}
