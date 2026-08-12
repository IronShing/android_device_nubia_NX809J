package com.nx809j.voiprecorder;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** App settings. Recording is OFF until the user accepts the consent screen. */
final class Prefs {
    private static final String FILE = "voiprec";
    private static final String K_ENABLED = "enabled";
    private static final String K_CONSENT = "consent";
    private static final String K_ALLOW = "allowlist";
    private static final String K_ALL_VOIP = "record_all_voip";
    private static final String K_HIDE_NOTIF = "hide_notification";

    // WhatsApp (consumer + business) by default; architecture supports any VoIP app.
    static final Set<String> DEFAULT_ALLOW =
            new HashSet<>(Arrays.asList("com.whatsapp", "com.whatsapp.w4b"));

    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    boolean isEnabled()   { return sp.getBoolean(K_ENABLED, false); }
    void setEnabled(boolean v) { sp.edit().putBoolean(K_ENABLED, v).apply(); }

    boolean hasConsent()  { return sp.getBoolean(K_CONSENT, false); }
    void setConsent(boolean v) { sp.edit().putBoolean(K_CONSENT, v).apply(); }

    boolean recordAllVoip() { return sp.getBoolean(K_ALL_VOIP, false); }
    void setRecordAllVoip(boolean v) { sp.edit().putBoolean(K_ALL_VOIP, v).apply(); }

    // When true: no persistent foreground service / idle notification — a
    // NotificationListenerService hosts detection and a notification shows only
    // while actually recording. Requires the user to grant Notification access.
    boolean hideNotification() { return sp.getBoolean(K_HIDE_NOTIF, false); }
    void setHideNotification(boolean v) { sp.edit().putBoolean(K_HIDE_NOTIF, v).apply(); }

    Set<String> allowlist() { return sp.getStringSet(K_ALLOW, DEFAULT_ALLOW); }
}
