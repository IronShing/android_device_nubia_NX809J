package com.nubia.rmcontrol;

import android.app.AlarmManager;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Privacy guard: per-app camera / microphone / location blocking that the app cannot see.
 *
 * The permission stays granted, so the app keeps believing it has access and never nags or
 * refuses to run; what changes is the switch app-op behind the permission, which the
 * framework pins to MODE_IGNORED for guarded packages. The effect is the "soft denied" path
 * the platform already has for restricted apps: the microphone records silence (audioserver
 * silences the record track, no permission error), the camera reports "disabled by policy"
 * and location listeners are simply never called. No privacy-indicator dot either.
 *
 * Plain AppOpsManager.setMode from here does NOT work: PermissionPolicyService re-derives
 * every runtime-permission op from the grant state on each op change, permission change and
 * user start, and would flip it straight back. So the rule set is published to
 * Settings.Secure {@link #SETTING} ("pkg:mask,pkg:mask") and our patch in
 * PermissionPolicyService (frameworks/base, nx809j) folds it into that same derivation --
 * the ignore then survives every resync and is re-applied at boot before this app even runs.
 *
 * "Allow for 10 minutes": a blocked attempt fires the op-rejected callbacks (WATCH_APPOPS),
 * we post a notification, and the action lifts the rule for that package until a deadline
 * stored in prefs; an exact alarm re-applies the guard when it passes.
 */
final class PrivacyGuard {
    private static final String TAG = "RMControl.Privacy";

    /** Master switch; rules are kept but not published while it is off. */
    static final String PROP_ENABLED = "persist.sys.rm.privacy";
    /** Read by PermissionPolicyService (frameworks/base patch). */
    static final String SETTING = "rm_privacy_guard";
    static final String PROP_AUTO = "persist.sys.rm.privacy_auto";  // auto-guard new installs
    /** Call-aware allow (default on): a guarded app that is in a call (CallDetect) gets its mic
     *  without asking, and the camera too when the call is video; both end with the call. */
    static final String PROP_CALLS = "persist.sys.rm.privacy_calls";
    /** Back off repeated background alerts (default on): an ignored "blocked" heads-up for the
     *  same app + permission waits 5x longer each time (1 min, 5, 25, ~2 h, 6 h cap) until the
     *  user answers one or the app comes to the front. */
    static final String PROP_BACKOFF = "persist.sys.rm.privacy_backoff";

    static final int CAMERA = 1;
    static final int MIC = 2;
    static final int LOCATION = 4;
    static final int CONTACTS = 8;
    static final int MEDIA_VISUAL = 16;   // photos & videos
    static final int MEDIA_AUDIO = 32;    // audio & music
    static final int FILES = 64;
    static final int NEARBY = 128;        // nearby devices (bluetooth)
    static final int PHONE = 256;         // phone state / call log
    static final int SMS = 512;           // read/receive SMS & MMS
    static final int CALENDAR = 1024;     // calendar
    static final int SENSORS = 2048;      // body sensors + physical activity
    static final int AD_ID = 4096;        // advertising ID -- NOT an app-op; framework AD_ID patch
    static final int ALL = CAMERA | MIC | LOCATION | CONTACTS | MEDIA_VISUAL | MEDIA_AUDIO | FILES
            | NEARBY | PHONE | SMS | CALENDAR | SENSORS | AD_ID;

    // All the app-op-backed bits (everything except AD_ID, which the framework AD_ID permission
    // patch handles rather than the app-op guard).
    static final int OP_BITS = ALL & ~AD_ID;

    /** Bits in a stable order for the per-app UI and iteration. */
    static final int[] BITS = { CAMERA, MIC, LOCATION, CONTACTS, MEDIA_VISUAL, MEDIA_AUDIO, FILES,
            NEARBY, PHONE, SMS, CALENDAR, SENSORS, AD_ID };
    static final String[] BIT_NAMES = { "Camera", "Mic", "Location", "Contacts", "Photos & videos",
            "Audio & music", "Files", "Nearby devices", "Phone & call log", "SMS", "Calendar",
            "Sensors & activity", "Advertising ID" };

    /** Glyph put in front of every prompt title so the permission is readable at a glance even
     *  when the title is truncated ("Google tried to use the…"). */
    static String permGlyph(int bit) {
        switch (bit) {
            case CAMERA: return "\uD83D\uDCF7";        // camera
            case MIC: return "\uD83C\uDFA4";           // microphone
            case LOCATION: return "\uD83D\uDCCD";      // round pushpin
            case CONTACTS: return "\uD83D\uDC64";      // bust in silhouette
            case MEDIA_VISUAL: return "\uD83D\uDDBC\uFE0F";   // framed picture
            case MEDIA_AUDIO: return "\uD83C\uDFB5";   // musical note
            case FILES: return "\uD83D\uDCC1";         // folder
            case NEARBY: return "\uD83D\uDCE1";        // satellite antenna
            case PHONE: return "\uD83D\uDCDE";         // telephone receiver
            case SMS: return "\uD83D\uDCAC";           // speech balloon
            case CALENDAR: return "\uD83D\uDCC5";      // calendar
            case SENSORS: return "\uD83E\uDDED";       // compass
            case AD_ID: return "\uD83C\uDFF7\uFE0F";  // label
            default: return "\uD83D\uDD12";            // lock
        }
    }

    /** Lower-case noun used in the "<app> tried to use the <x>" notification. */
    static String permWord(int bit) {
        switch (bit) {
            case CAMERA: return "camera";
            case MIC: return "microphone";
            case LOCATION: return "location";
            case CONTACTS: return "contacts";
            case MEDIA_VISUAL: return "photos & videos";
            case MEDIA_AUDIO: return "audio";
            case FILES: return "files";
            case NEARBY: return "nearby devices";
            case PHONE: return "phone & call log";
            case SMS: return "SMS";
            case CALENDAR: return "calendar";
            case SENSORS: return "sensors";
            case AD_ID: return "advertising ID";
            default: return "data";
        }
    }

    /** Android partial photo access: READ_MEDIA_VISUAL_USER_SELECTED granted, full photo/video not. */
    static boolean partialPhotoAccess(Context ctx, String pkg) {
        final PackageManager pm = ctx.getPackageManager();
        return pm.checkPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, pkg)
                        == PackageManager.PERMISSION_GRANTED
                && pm.checkPermission(android.Manifest.permission.READ_MEDIA_IMAGES, pkg)
                        != PackageManager.PERMISSION_GRANTED
                && pm.checkPermission(android.Manifest.permission.READ_MEDIA_VIDEO, pkg)
                        != PackageManager.PERMISSION_GRANTED;
    }

    /** Comma list of {@link #permWord} for every bit set in mask. */
    static String permWords(int mask) {
        final StringBuilder sb = new StringBuilder();
        for (int b : BITS) {
            if ((mask & b) == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(permWord(b));
        }
        return sb.toString();
    }

    static final long ALLOW_MS = 10 * 60 * 1000L;
    /** Durations offered for a temporary per-permission allowance. */
    static final long[] ALLOW_CHOICES_MS = { 5 * 60 * 1000L, 10 * 60 * 1000L };

    /** Android manifest permissions that map to a guard bit (for only-show-requested filtering). */
    static String[] permsForBit(int bit) {
        switch (bit) {
            case CAMERA: return new String[]{ "android.permission.CAMERA" };
            case MIC: return new String[]{ "android.permission.RECORD_AUDIO" };
            case LOCATION: return new String[]{ "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION" };
            case CONTACTS: return new String[]{ "android.permission.READ_CONTACTS",
                    "android.permission.WRITE_CONTACTS" };
            case MEDIA_VISUAL: return new String[]{ "android.permission.READ_MEDIA_IMAGES",
                    "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_EXTERNAL_STORAGE" };
            case MEDIA_AUDIO: return new String[]{ "android.permission.READ_MEDIA_AUDIO" };
            case FILES: return new String[]{ "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE" };
            case NEARBY: return new String[]{ "android.permission.BLUETOOTH_CONNECT",
                    "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADVERTISE" };
            case PHONE: return new String[]{ "android.permission.READ_PHONE_STATE",
                    "android.permission.READ_PHONE_NUMBERS", "android.permission.READ_CALL_LOG",
                    "android.permission.WRITE_CALL_LOG" };
            case SMS: return new String[]{ "android.permission.READ_SMS", "android.permission.RECEIVE_SMS",
                    "android.permission.SEND_SMS", "android.permission.RECEIVE_MMS" };
            case CALENDAR: return new String[]{ "android.permission.READ_CALENDAR",
                    "android.permission.WRITE_CALENDAR" };
            case SENSORS: return new String[]{ "android.permission.BODY_SENSORS",
                    "android.permission.ACTIVITY_RECOGNITION" };
            case AD_ID: return new String[]{ "com.google.android.gms.permission.AD_ID" };
            default: return new String[0];
        }
    }

    /** Default guard mask when an app is added: only the permissions it actually requests, plus the
     *  invisible Advertising ID (no manifest permission, default-on to block ad-ID/tracking). A
     *  permission-less app (e.g. Calculator) then comes in guarding just AD_ID, not all twelve. */
    static int requestedMask(Context ctx, String pkg) {
        int m = AD_ID;
        for (int b : BITS) {
            if (b == AD_ID) continue;
            if (appRequestsBit(ctx, pkg, b)) m |= b;
        }
        return m;
    }

    /** True if the app declares any permission mapping to this bit (only-show-requested filter). */
    static boolean appRequestsBit(Context ctx, String pkg, int bit) {
        try {
            final String[] req = ctx.getPackageManager()
                    .getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (req == null) return false;
            final java.util.HashSet<String> set =
                    new java.util.HashSet<>(java.util.Arrays.asList(req));
            for (String p : permsForBit(bit)) if (set.contains(p)) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static final String PREFS = "privacy";
    private static final String KEY_MASK = "mask:";      // + pkg -> int
    private static final String KEY_UNTIL = "until:";    // + pkg -> long wall-clock ms
    private static final String KEY_MUTE = "mute:";      // + pkg -> boolean: no "blocked" alerts or prompts
    private static final String KEY_AA = "aa:";          // + pkg -> boolean: lift mic/loc during Android Auto
    private static final String KEY_CALLASK = "callask:"; // + pkg -> boolean: ask even during its calls
    private static final String KEY_CALLBOUND = "call:"; // + pkg:bit -> boolean: allowance ends with the call
    /** Safety cap on a call-bound allowance in case every "call ended" signal is missed. */
    private static final long CALL_ALLOW_CAP_MS = 6 * 60 * 60 * 1000L;
    /** Re-check the call signals this often while a call-bound allowance is up. */
    private static final long CALL_POLL_MS = 15 * 1000L;
    /** A camera attempt this soon after the call began is the app warming up for a possible
     *  video switch (WhatsApp opens it on plain voice calls too), not the user turning video on:
     *  blocked quietly. Later, with the app on screen, it is asked about like any other attempt. */
    private static final long CALL_CAMERA_GRACE_MS = 10 * 1000L;
    /** Let the call notification / record client land before deciding what a mic attempt is. */
    private static final long CALL_DECIDE_DELAY_MS = 600;
    /** After a call ends the app tears down and grabs the mic/camera once more (WhatsApp does,
     *  ~1 s after hang-up); alerts for that are noise, so sensor attempts stay quiet this long. */
    private static final long CALL_END_GRACE_MS = 10 * 1000L;
    // Screen rule: + pkg/activityClass:bit -> long ms. While that activity is in front the
    // permission is auto-allowed for ms (renewed while it stays in front). Learnt from the
    // "always allow on this screen" box in the foreground prompt (e.g. Google Lens = the Google
    // app's lens.MainActivity, so the camera can be lifted for Lens alone, not for all of Google).
    // ms == SCREEN_BLOCK is the opposite rule ("always block on this screen"): stays guarded and
    // the prompt is skipped on that screen only (WhatsApp's call screen probing location on
    // every call, 2026-09-19), while the same ask elsewhere in the app still prompts.
    private static final String KEY_AUTO = "auto:";
    static final long SCREEN_BLOCK = -1L;
    static final String EXTRA_CLS = "cls";

    /** Ops lifted for an AA-allowed package while Android Auto (car mode) is connected. */
    static final int AA_EXEMPT = MIC | LOCATION;
    /** Set by CarModeWatcher from UiModeManager ENTER/EXIT_CAR_MODE (Android Auto projection). */
    private static volatile boolean sAaConnected;

    static final String ACTION_ALLOW = "com.nubia.rmcontrol.PRIVACY_ALLOW";
    static final String ACTION_REAPPLY = "com.nubia.rmcontrol.PRIVACY_REAPPLY";
    // Top up an active (or just-expired) allowance from the ongoing countdown notification.
    static final String ACTION_EXTEND = "com.nubia.rmcontrol.PRIVACY_EXTEND";
    // Per-allowance expiry alarm: drop the countdown notification, re-guard, and re-prompt.
    static final String ACTION_ALLOW_EXPIRED = "com.nubia.rmcontrol.PRIVACY_ALLOW_EXPIRED";
    // "Guard now" on the in-call note: end the package's call-bound allowances early.
    static final String ACTION_END_CALL_ALLOW = "com.nubia.rmcontrol.PRIVACY_END_CALL_ALLOW";
    // "Always block" on a blocked / expired prompt: keep the guard, stop asking (= the per-app
    // alert mute in the app's Privacy row, where it can be switched off again).
    static final String ACTION_MUTE = "com.nubia.rmcontrol.PRIVACY_MUTE";
    static final String EXTRA_PKG = "pkg";
    static final String EXTRA_BIT = "bit";
    static final String EXTRA_MS = "ms";
    // Tapping the "blocked" notification deep-links straight to this app's Privacy Guard editor.
    static final String EXTRA_OPEN_EDITOR = "open_privacy_editor";
    private static final String CHANNEL = "privacy_guard";
    /** Heads-up channel for the prompts that want an answer (blocked / access ended). A channel's
     *  importance is frozen once created, so the old DEFAULT channel keeps the quiet status notes. */
    private static final String CHANNEL_ASK = "privacy_guard_ask";

    /** Minimum gap between notifications for the same package. */
    private static final long NOTIFY_GAP_MS = 60 * 1000L;
    /** Media/files/contacts/... prompts: a picker re-queries on every scroll, so one ask per 5 min. */
    private static final long NOTIFY_GAP_QUIET_MS = 5 * 60 * 1000L;
    // Location is polled constantly in the background; a per-attempt notification is just noise.
    // Camera/mic are discrete events worth surfacing, so they keep the short gap.
    private static final long NOTIFY_GAP_LOCATION_MS = 6 * 60 * 60 * 1000L;

    private static Handler sHandler;
    private static boolean sWatching;
    private static final ArrayMap<String, Long> sLastNotified = new ArrayMap<>();

    private PrivacyGuard() { }

    // ---- rules ----------------------------------------------------------------------------

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled() {
        return Prop.getBool(PROP_ENABLED, false);
    }

    static void setEnabled(Context ctx, boolean on) {
        Prop.set(PROP_ENABLED, on ? "1" : "0");
        apply(ctx);
    }

    /** package -> mask (never 0), sorted by package. */
    static TreeMap<String, Integer> rules(Context ctx) {
        final TreeMap<String, Integer> out = new TreeMap<>();
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            if (!e.getKey().startsWith(KEY_MASK) || !(e.getValue() instanceof Integer)) continue;
            final int m = (Integer) e.getValue();
            if (m != 0) out.put(e.getKey().substring(KEY_MASK.length()), m);
        }
        return out;
    }

    static int maskOf(Context ctx, String pkg) {
        return prefs(ctx).getInt(KEY_MASK + pkg, 0);
    }

    /** mask 0 removes the rule (and any temporary allowance). */
    static void setMask(Context ctx, String pkg, int mask) {
        final SharedPreferences.Editor ed = prefs(ctx).edit();
        if (mask == 0) {
            ed.remove(KEY_MASK + pkg).remove(KEY_AA + pkg).remove(KEY_CALLASK + pkg);
            removeMuteKeys(ed, prefs(ctx).getAll(), pkg);
            revokeAllAllows(ctx, ed, pkg);
            removeAutoRules(ed, prefs(ctx).getAll(), pkg);
        }
        else ed.putInt(KEY_MASK + pkg, mask & ALL);
        ed.apply();
        apply(ctx);
    }

    static boolean autoEnabled() {
        return Prop.getBool(PROP_AUTO, false);
    }

    static boolean callAware() { return Prop.getBool(PROP_CALLS, true); }
    static void setCallAware(Context ctx, boolean on) {
        Prop.set(PROP_CALLS, on ? "1" : "0");
        CenterNotifListener.ensureEnabled(ctx);   // the listener grant follows the need
        if (!on) endAllCallAllows(ctx);
    }
    static boolean backoff() { return Prop.getBool(PROP_BACKOFF, true); }
    static void setBackoff(Context ctx, boolean on) {
        Prop.set(PROP_BACKOFF, on ? "1" : "0");
        synchronized (sIgnored) { sIgnored.clear(); }
    }

    /** Per-app opt-out of call-aware allow: ask this app's calls like any other attempt. */
    static boolean callAsk(Context ctx, String pkg) {
        return prefs(ctx).getBoolean(KEY_CALLASK + pkg, false);
    }
    static void setCallAsk(Context ctx, String pkg, boolean on) {
        final SharedPreferences.Editor ed = prefs(ctx).edit();
        if (on) ed.putBoolean(KEY_CALLASK + pkg, true); else ed.remove(KEY_CALLASK + pkg);
        ed.apply();
        if (on) endCallAllows(ctx, pkg);
    }

    static void setAutoEnabled(Context ctx, boolean on) {
        Prop.set(PROP_AUTO, on ? "1" : "0");
    }

    /** A freshly installed (non-system, non-updated) app: guard it fully when auto-guard is on. */
    static void onPackageAdded(Context ctx, String pkg) {
        if (pkg == null || pkg.equals(ctx.getPackageName()) || !autoEnabled()) return;
        if (maskOf(ctx, pkg) != 0) return;   // already has a rule
        try {
            final android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(pkg, 0);
            if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) return;
        } catch (Exception e) { return; }
        Log.i(TAG, "auto-guard new install: " + pkg);
        setMask(ctx, pkg, requestedMask(ctx, pkg));
        if (!enabled()) setEnabled(ctx, true);
    }

    /** Wall-clock deadline until which the package is temporarily un-guarded, or 0. */
    /**
     * Per-app alert mute. The guard still applies -- this only silences the "<app> tried to use
     * the …" heads-up and in-front prompt for apps that poll constantly (a chat app trying the
     * mic on every call, a launcher polling location) where the alert is noise. The "Allow for
     * 10 min" action is still reachable from the app's row in the Privacy tab; a single noisy
     * screen is better handled by the per-screen quiet rule (SCREEN_BLOCK).
     */
    // Per permission since 2026-09-21: "Always block" on a Contacts prompt used to silence the
    // app's Location prompts too (Careem never asked for location after its onboarding ask was
    // muted). "mute:<pkg>" (legacy, whole app) still reads as every permission; "mute:<pkg>:<bit>"
    // quiets just that one, so the other guarded permissions of the app keep prompting.
    static boolean muted(Context ctx, String pkg, int bit) {
        final SharedPreferences p = prefs(ctx);
        return p.getBoolean(KEY_MUTE + pkg, false) || p.getBoolean(KEY_MUTE + pkg + ":" + bit, false);
    }

    /** Bits of pkg whose alerts are muted (ALL for the legacy whole-app mute). */
    static int mutedMask(Context ctx, String pkg) {
        final SharedPreferences p = prefs(ctx);
        if (p.getBoolean(KEY_MUTE + pkg, false)) return ALL;
        int m = 0;
        for (int b = 1; b != 0 && b <= ALL; b <<= 1) if (p.getBoolean(KEY_MUTE + pkg + ":" + b, false)) m |= b;
        return m;
    }

    /** True when every currently guarded permission of pkg is muted (the editor's app-wide box). */
    static boolean muted(Context ctx, String pkg) {
        final int mask = maskOf(ctx, pkg);
        return mask != 0 && (mutedMask(ctx, pkg) & mask) == mask;
    }

    /** Whole-app mute on/off (editor checkbox): on = legacy key; off = clears every mute key. */
    static void setMuted(Context ctx, String pkg, boolean on) {
        final SharedPreferences.Editor ed = prefs(ctx).edit();
        if (on) ed.putBoolean(KEY_MUTE + pkg, true);
        else removeMuteKeys(ed, prefs(ctx).getAll(), pkg);
        ed.apply();
        if (on) cancelBlockedAlerts(ctx, pkg, ALL);
    }

    /** Mute / un-mute one permission's alerts. Un-muting one bit under a legacy whole-app mute
     *  expands that mute into per-bit keys for the rest first. */
    static void setMuted(Context ctx, String pkg, int bit, boolean on) {
        final SharedPreferences p = prefs(ctx);
        final SharedPreferences.Editor ed = p.edit();
        if (on) {
            ed.putBoolean(KEY_MUTE + pkg + ":" + bit, true);
        } else {
            if (p.getBoolean(KEY_MUTE + pkg, false)) {
                ed.remove(KEY_MUTE + pkg);
                final int mask = maskOf(ctx, pkg);
                for (int b = 1; b != 0 && b <= ALL; b <<= 1) if ((mask & b) != 0 && b != bit) ed.putBoolean(KEY_MUTE + pkg + ":" + b, true);
            }
            ed.remove(KEY_MUTE + pkg + ":" + bit);
        }
        ed.apply();
        Log.i(TAG, (on ? "always block " : "ask again ") + pkg + " " + permWord(bit));
        if (on) cancelBlockedAlerts(ctx, pkg, bit);
    }

    private static void removeMuteKeys(SharedPreferences.Editor ed, Map<String, ?> all, String pkg) {
        ed.remove(KEY_MUTE + pkg);
        for (String k : all.keySet()) if (k.startsWith(KEY_MUTE + pkg + ":")) ed.remove(k);
    }

    /** Take down the "blocked" heads-up / expired prompts for the bits just muted (keeps live countdowns). */
    private static void cancelBlockedAlerts(Context ctx, String pkg, int bits) {
        cancelNotification(ctx, pkg);
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        for (int b = 1; b != 0 && b <= ALL; b <<= 1) if ((bits & b) != 0 && allowedUntil(ctx, pkg, b) == 0) nm.cancel(pkg, b);
    }

    // ---- screen rules: auto-allow while a specific activity is in front --------------------

    /** Top activity of the focused task (from the TaskStackListener), or null. */
    private static volatile ComponentName sTop;
    /** Package (and its screen) the foreground prompt is currently up for, else null. */
    static volatile String sPromptPkg;
    static volatile String sPromptCls;

    static ComponentName top() { return sTop; }

    private static String autoKey(String pkg, String cls, int bit) {
        return KEY_AUTO + pkg + "/" + cls + ":" + bit;
    }

    /**
     * ms the rule auto-allows for, SCREEN_BLOCK for a quiet-block rule, or 0 if there is no
     * rule for this screen + permission.
     */
    static long autoAllowMs(Context ctx, String pkg, String cls, int bit) {
        return prefs(ctx).getLong(autoKey(pkg, cls, bit), 0);
    }

    /** ms > 0 = allow rule, SCREEN_BLOCK = quiet-block rule, 0 removes the rule. */
    static void setAutoAllow(Context ctx, String pkg, String cls, int bit, long ms) {
        final SharedPreferences.Editor ed = prefs(ctx).edit();
        if (ms != 0) ed.putLong(autoKey(pkg, cls, bit), ms); else ed.remove(autoKey(pkg, cls, bit));
        ed.apply();
        Log.i(TAG, (ms > 0 ? "screen rule " : ms == 0 ? "screen rule removed " : "screen block rule ")
                + pkg + "/" + cls + " " + permWord(bit) + (ms > 0 ? " " + (ms / 60000) + " min" : ""));
        if (ms > 0) checkAutoAllow(ctx, sTop);
    }

    /** {cls, bit, ms} rows for one package, for the editor. */
    static List<Object[]> autoRules(Context ctx, String pkg) {
        final List<Object[]> out = new ArrayList<>();
        final String prefix = KEY_AUTO + pkg + "/";
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            if (!e.getKey().startsWith(prefix) || !(e.getValue() instanceof Long)) continue;
            final String rest = e.getKey().substring(prefix.length());
            final int colon = rest.lastIndexOf(':');
            if (colon < 0) continue;
            try {
                out.add(new Object[] { rest.substring(0, colon),
                        Integer.parseInt(rest.substring(colon + 1)), (Long) e.getValue() });
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** Human name for a screen rule's activity (its label if it has one, else the class tail). */
    static String screenLabel(Context ctx, String pkg, String cls) {
        try {
            final CharSequence l = ctx.getPackageManager()
                    .getActivityInfo(new ComponentName(pkg, cls), 0).loadLabel(ctx.getPackageManager());
            if (l != null && l.length() > 0 && !l.toString().equals(label(ctx, pkg))) return l.toString();
        } catch (Exception ignored) { }
        return cls.substring(cls.lastIndexOf('.') + 1);
    }

    private static void removeAutoRules(SharedPreferences.Editor ed, Map<String, ?> all, String pkg) {
        final String prefix = KEY_AUTO + pkg + "/";
        for (String k : all.keySet()) if (k.startsWith(prefix)) ed.remove(k);
    }

    /** Follow the foreground activity. System uid: no MANAGE_ACTIVITY_TASKS grant needed. */
    private static void startForegroundWatch(Context ctx) {
        try {
            ActivityTaskManager.getService().registerTaskStackListener(new TaskStackListener() {
                @Override public void onTaskStackChanged() { refreshTop(ctx); }
                @Override public void onTaskMovedToFront(android.app.ActivityManager.RunningTaskInfo ti) {
                    refreshTop(ctx);
                }
            });
            refreshTop(ctx);
        } catch (Throwable t) {
            Log.e(TAG, "cannot watch the foreground activity; screen rules + foreground prompt are inert", t);
        }
    }

    private static void refreshTop(Context ctx) {
        sHandler.post(() -> {
            ComponentName cn = null;
            try {
                final android.app.ActivityTaskManager.RootTaskInfo ti =
                        ActivityTaskManager.getService().getFocusedRootTaskInfo();
                if (ti != null) cn = ti.topActivity != null ? ti.topActivity : ti.baseActivity;
            } catch (Throwable ignored) { }
            if (cn == null || cn.equals(sTop)) return;
            sTop = cn;
            checkAutoAllow(ctx, cn);
        });
    }

    /** A screen with a rule came to the front: lift its permission now, before the app asks. */
    private static void checkAutoAllow(Context ctx, ComponentName cn) {
        if (cn == null || !enabled()) return;
        final String pkg = cn.getPackageName();
        final int mask = maskOf(ctx, pkg);
        if (mask == 0) return;
        for (int b : BITS) {
            if ((mask & b) == 0 || allowedUntil(ctx, pkg, b) != 0) continue;
            final long ms = autoAllowMs(ctx, pkg, cn.getClassName(), b);
            if (ms > 0) {
                Log.i(TAG, "screen rule hit: " + cn.flattenToShortString() + " " + permWord(b));
                allowFor(ctx, pkg, b, ms);
            }
        }
    }

    /** Does a screen rule cover this package + permission for the activity in front right now? */
    private static long autoAllowForTop(Context ctx, String pkg, int bit) {
        final ComponentName cn = sTop;
        if (cn == null || !cn.getPackageName().equals(pkg)) return 0;
        return autoAllowMs(ctx, pkg, cn.getClassName(), bit);
    }

    // ---- Android Auto exemption -----------------------------------------------------------
    // Android Auto asks the Google app for the microphone (and maps needs location); Privacy
    // Guard would deny both. Per-app opt-in: while AA is connected, lift MIC|LOCATION for apps
    // the user marked "allow during Android Auto", then re-guard on disconnect. The lift is
    // folded into the published mask in apply(), so it survives every framework resync exactly
    // like the base guard, and needs no extra framework patch.

    static boolean aaAllowed(Context ctx, String pkg) {
        return prefs(ctx).getBoolean(KEY_AA + pkg, false);
    }

    static void setAaAllowed(Context ctx, String pkg, boolean on) {
        final SharedPreferences.Editor ed = prefs(ctx).edit();
        if (on) ed.putBoolean(KEY_AA + pkg, true); else ed.remove(KEY_AA + pkg);
        ed.apply();
        apply(ctx);
    }

    /** Android Auto (car mode) connected state; republish so the exemption applies/lifts now. */
    static void setAaConnected(Context ctx, boolean connected) {
        if (sAaConnected == connected) return;
        sAaConnected = connected;
        Log.i(TAG, "Android Auto connected=" + connected);
        apply(ctx);
    }

    private static String untilKey(String pkg, int bit) { return KEY_UNTIL + pkg + ":" + bit; }

    /** Wall-clock deadline this one permission is temporarily allowed until, or 0. */
    static long allowedUntil(Context ctx, String pkg, int bit) {
        final long t = prefs(ctx).getLong(untilKey(pkg, bit), 0);
        return t > System.currentTimeMillis() ? t : 0;
    }

    /** Is any permission of this app on a temporary allowance right now? */
    static boolean anyAllowed(Context ctx, String pkg) {
        final long now = System.currentTimeMillis();
        for (int b : BITS) if (prefs(ctx).getLong(untilKey(pkg, b), 0) > now) return true;
        return false;
    }

    /** Temporarily un-guard ONE permission of an app for ms milliseconds. */
    static void allowFor(Context ctx, String pkg, int bit, long ms) {
        prefs(ctx).edit().putLong(untilKey(pkg, bit), System.currentTimeMillis() + ms)
                .remove(callKey(pkg, bit)).apply();
        Log.i(TAG, "allow " + pkg + " " + permWord(bit) + " for " + (ms / 1000) + " s");
        synchronized (sIgnored) { sIgnored.remove(pkg + ":" + bit); }   // the user answered
        apply(ctx);
        cancelNotification(ctx, pkg);            // take down the "blocked / allow?" prompt
        notifyAllowed(ctx, pkg, bit);            // put up the live countdown + Extend
        scheduleAllowExpiry(ctx, pkg, bit);
    }

    /** Add more time to an active (or just-expired) allowance — from the Extend button. Additive so
     *  "+5" never shortens a window that still has more than 5 min left. */
    static void allowExtend(Context ctx, String pkg, int bit, long addMs) {
        final long base = Math.max(System.currentTimeMillis(), prefs(ctx).getLong(untilKey(pkg, bit), 0));
        prefs(ctx).edit().putLong(untilKey(pkg, bit), base + addMs).apply();
        Log.i(TAG, "extend " + pkg + " " + permWord(bit) + " +" + (addMs / 1000) + " s");
        apply(ctx);
        cancelNotification(ctx, pkg);
        notifyAllowed(ctx, pkg, bit);
        scheduleAllowExpiry(ctx, pkg, bit);
    }

    /** A temporary allowance ran out: drop the countdown notification, re-guard, re-prompt to renew. */
    static void onAllowExpired(Context ctx, String pkg, int bit) {
        if (prefs(ctx).getLong(untilKey(pkg, bit), 0) > System.currentTimeMillis()) return;  // was extended
        final boolean callBound = prefs(ctx).getBoolean(callKey(pkg, bit), false);
        prefs(ctx).edit().remove(untilKey(pkg, bit)).remove(callKey(pkg, bit)).apply();
        ctx.getSystemService(NotificationManager.class).cancel(pkg, bit);   // the countdown notif
        apply(ctx);                                                          // re-guard the bit
        markReguarded(pkg);
        if (callBound) { Log.i(TAG, "call allowance cap hit " + pkg + " " + permWord(bit)); return; }
        if ((maskOf(ctx, pkg) & bit) != 0) {                                 // still guarded → offer renew
            final long auto = autoAllowForTop(ctx, pkg, bit);
            if (auto > 0) { allowFor(ctx, pkg, bit, auto); return; }         // its screen is still up: renew
            // Contacts/photos/files/...: only worth a renew offer while the app is being used;
            // otherwise it simply asks again (on screen) the next time it needs them.
            if (!isSensorBit(bit)) {
                final ComponentName top = sTop;
                if (top == null || !top.getPackageName().equals(pkg)) {
                    Log.i(TAG, "allowance ended quietly " + pkg + " " + permWord(bit));
                    return;
                }
            }
            sLastNotified.remove(pkg + ":" + bit);
            notifyExpiredPrompt(ctx, pkg, bit);
        }
    }

    private static void scheduleAllowExpiry(Context ctx, String pkg, int bit) {
        final long until = prefs(ctx).getLong(untilKey(pkg, bit), 0);
        if (until <= System.currentTimeMillis()) return;
        ctx.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, until + 1000, allowExpiryIntent(ctx, pkg, bit));
    }

    /** Manual "guard again now": drop the allowance AND its countdown notification + expiry alarm,
     *  otherwise the chronometer keeps running and the alarm later posts a bogus "access ended". */
    static void revokeAllow(Context ctx, String pkg, int bit) {
        prefs(ctx).edit().remove(untilKey(pkg, bit)).remove(callKey(pkg, bit)).apply();
        cancelAllowExpiry(ctx, pkg, bit);
        ctx.getSystemService(NotificationManager.class).cancel(pkg, bit);
        apply(ctx);
    }

    /** Drop every temporary allowance for a package (used when its whole rule is removed). */
    private static void revokeAllAllows(Context ctx, SharedPreferences.Editor ed, String pkg) {
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        for (int b : BITS) {
            if (prefs(ctx).contains(untilKey(pkg, b))) { cancelAllowExpiry(ctx, pkg, b); nm.cancel(pkg, b); }
            ed.remove(untilKey(pkg, b)).remove(callKey(pkg, b));
        }
    }

    // ---- call-aware allow -----------------------------------------------------------------
    // A call is the one time nearly everyone wants the mic (and, for video, the camera) to just
    // work, and it is also the one mic use the platform can vouch for: see CallDetect. So while a
    // guarded app is in a call its mic is lifted without a prompt, the camera too on a video
    // call, and both are guarded again the moment the call ends. Location is never part of it.

    private static String callKey(String pkg, int bit) { return KEY_CALLBOUND + pkg + ":" + bit; }

    /** elapsedRealtime the package was first seen in a call (for the camera grace), else absent. */
    private static final ArrayMap<String, Long> sCallSince = new ArrayMap<>();
    /** elapsedRealtime a package's call-bound allowance was ended (for the hang-up grace). */
    private static final ArrayMap<String, Long> sCallEnded = new ArrayMap<>();

    /** elapsedRealtime a package was automatically re-guarded (allowance expiry, call end). Apps
     *  re-check every op the moment its mode flips, and those checks arrive here as "attempts";
     *  alerting on them would nag about nothing, so attempts this soon after are ignored. */
    private static final ArrayMap<String, Long> sReguarded = new ArrayMap<>();
    private static final long REGUARD_GRACE_MS = 3 * 1000L;

    private static void markReguarded(String pkg) {
        synchronized (sReguarded) { sReguarded.put(pkg, SystemClock.elapsedRealtime()); }
    }
    private static boolean justReguarded(String pkg) {
        final Long t;
        synchronized (sReguarded) { t = sReguarded.get(pkg); }
        return t != null && SystemClock.elapsedRealtime() - t < REGUARD_GRACE_MS;
    }

    private static boolean justEndedCall(String pkg) {
        final Long t;
        synchronized (sCallEnded) { t = sCallEnded.get(pkg); }
        return t != null && SystemClock.elapsedRealtime() - t < CALL_END_GRACE_MS;
    }

    private static boolean callAllowApplies(Context ctx, String pkg, int bit) {
        return callAware() && (bit == MIC || bit == CAMERA) && !callAsk(ctx, pkg);
    }

    /** Mic / camera attempt from a call-eligible app: classify it once the signals have settled. */
    private static void decideCallAttempt(Context ctx, String pkg, int bit) {
        if (allowedUntil(ctx, pkg, bit) != 0) return;            // lifted meanwhile
        final int call = CallDetect.of(ctx, pkg);
        final long now = SystemClock.elapsedRealtime();
        long since;
        synchronized (sCallSince) {
            if (call == CallDetect.NONE) { sCallSince.remove(pkg); since = now; }
            else {
                final Long t = sCallSince.get(pkg);
                if (t == null) sCallSince.put(pkg, since = now); else since = t;
            }
        }
        if (call == CallDetect.NONE) {                           // a plain mic/camera grab: ask
            if (justEndedCall(pkg)) { Log.i(TAG, "post-call " + permWord(bit) + " blocked quietly: " + pkg); return; }
            if (isProbeBurst(pkg)) { Log.i(TAG, "permission probe, quiet: " + pkg + " " + permWord(bit)); return; }
            notifyBlocked(ctx, pkg, bit);
            return;
        }
        if (bit == MIC || call == CallDetect.VIDEO) {
            Log.i(TAG, "in-call allow " + pkg + " " + permWord(bit) + " (" + CallDetect.name(call) + ")");
            allowForCall(ctx, pkg, bit);
            return;
        }
        // Camera on a voice call. Early = the app warming up, quiet; later with the app in front
        // = the user is switching to video, so ask (the dialog is for the camera alone).
        final ComponentName top = sTop;
        final boolean inFront = top != null && top.getPackageName().equals(pkg);
        if (inFront && now - since >= CALL_CAMERA_GRACE_MS) {
            notifyBlocked(ctx, pkg, bit);
        } else {
            Log.i(TAG, "camera during voice call blocked quietly: " + pkg);
        }
    }

    /** Lift one permission until the call ends (capped), with a quiet "until the call ends" note. */
    private static void allowForCall(Context ctx, String pkg, int bit) {
        prefs(ctx).edit().putLong(untilKey(pkg, bit), System.currentTimeMillis() + CALL_ALLOW_CAP_MS)
                .putBoolean(callKey(pkg, bit), true).apply();
        apply(ctx);
        cancelNotification(ctx, pkg);
        notifyCallAllowed(ctx, pkg, bit);
        scheduleAllowExpiry(ctx, pkg, bit);
        sHandler.removeCallbacks(sCallPoll);
        sHandler.postDelayed(sCallPoll, CALL_POLL_MS);
    }

    /** Packages with a call-bound allowance up right now. */
    private static List<String> callBoundPkgs(Context ctx) {
        final List<String> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            if (!e.getKey().startsWith(KEY_CALLBOUND) || !Boolean.TRUE.equals(e.getValue())) continue;
            final String rest = e.getKey().substring(KEY_CALLBOUND.length());
            final int colon = rest.lastIndexOf(':');
            if (colon > 0 && !out.contains(rest.substring(0, colon))) out.add(rest.substring(0, colon));
        }
        return out;
    }

    private static Context sApp;
    private static final Runnable sCallPoll = PrivacyGuard::pollCalls;
    private static void pollCalls() {
        final Context ctx = sApp;
        if (ctx == null) return;
        boolean any = false;
        for (String pkg : callBoundPkgs(ctx)) {
            if (CallDetect.of(ctx, pkg) == CallDetect.NONE) endCallAllows(ctx, pkg);
            else any = true;
        }
        if (any) sHandler.postDelayed(sCallPoll, CALL_POLL_MS);
    }

    /** The listener saw the package's call notification go: re-check soon (the FGS and the record
     *  client usually linger a moment; the poll confirms before re-guarding). */
    static void onCallNotificationGone(Context ctx, String pkg) {
        if (sHandler == null || !callAware()) return;
        sHandler.postDelayed(() -> {
            final Context app = sApp;
            if (app != null && callBoundPkgs(app).contains(pkg)
                    && CallDetect.of(app, pkg) == CallDetect.NONE) endCallAllows(app, pkg);
        }, 1500);
    }

    /** The call is over: drop the package's call-bound allowances and its note, guard again. */
    static void endCallAllows(Context ctx, String pkg) {
        final SharedPreferences.Editor ed = prefs(ctx).edit();
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        boolean changed = false;
        for (int b : BITS) {
            if (!prefs(ctx).getBoolean(callKey(pkg, b), false)) continue;
            ed.remove(untilKey(pkg, b)).remove(callKey(pkg, b));
            cancelAllowExpiry(ctx, pkg, b);
            nm.cancel(pkg, b);
            changed = true;
        }
        synchronized (sCallSince) { sCallSince.remove(pkg); }
        if (!changed) return;
        ed.apply();
        synchronized (sCallEnded) { sCallEnded.put(pkg, SystemClock.elapsedRealtime()); }
        Log.i(TAG, "call ended: " + pkg + " guarded again");
        apply(ctx);
        markReguarded(pkg);
    }

    private static void endAllCallAllows(Context ctx) {
        for (String pkg : callBoundPkgs(ctx)) endCallAllows(ctx, pkg);
    }

    /** Quiet ongoing "X is in a call — mic allowed until it ends" with a Guard-now action. */
    private static void notifyCallAllowed(Context ctx, String pkg, int bit) {
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Privacy guard",
                NotificationManager.IMPORTANCE_DEFAULT));
        final PendingIntent guard = PendingIntent.getBroadcast(ctx, (pkg + ":endcall:" + bit).hashCode(),
                new Intent(ACTION_END_CALL_ALLOW).setClass(ctx, PrivacyGuardReceiver.class)
                        .putExtra(EXTRA_PKG, pkg),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_privacy)
                .setContentTitle("\uD83D\uDCDE " + label(ctx, pkg) + " is in a call \u2014 "
                        + permWord(bit) + " allowed")
                .setContentText("Guarded again when the call ends.")
                .setContentIntent(editorIntent(ctx, pkg))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .addAction(new Notification.Action.Builder(null, "Guard now", guard).build())
                .build();
        nm.notify(pkg, bit, n);
    }

    private static PendingIntent allowExpiryIntent(Context ctx, String pkg, int bit) {
        return PendingIntent.getBroadcast(ctx, (pkg + ":exp:" + bit).hashCode(),
                new Intent(ACTION_ALLOW_EXPIRED).setClass(ctx, PrivacyGuardReceiver.class)
                        .putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_BIT, bit),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static void cancelAllowExpiry(Context ctx, String pkg, int bit) {
        ctx.getSystemService(AlarmManager.class).cancel(allowExpiryIntent(ctx, pkg, bit));
    }

    // ---- publish to the framework ---------------------------------------------------------

    /**
     * Recompute the effective rule string, push it to Settings.Secure only when it differs
     * (each write costs a full permission/app-op resync in system_server) and arm the alarm
     * for the earliest temporary allowance to expire.
     */
    static synchronized void apply(Context ctx) {
        final StringBuilder sb = new StringBuilder();
        long nextExpiry = Long.MAX_VALUE;
        if (enabled()) {
            final long now = System.currentTimeMillis();
            final SharedPreferences p = prefs(ctx);
            for (Map.Entry<String, Integer> r : rules(ctx).entrySet()) {
                final String pkg = r.getKey();
                int m = r.getValue();
                // Per-permission temporary allowances: clear each bit whose deadline is in the future.
                for (int b : BITS) {
                    if ((m & b) == 0) continue;
                    final long until = p.getLong(untilKey(pkg, b), 0);
                    if (until > now) { m &= ~b; nextExpiry = Math.min(nextExpiry, until); }
                }
                // Android Auto: lift mic/location for opted-in apps while AA (car mode) is connected.
                if (sAaConnected && p.getBoolean(KEY_AA + pkg, false)) {
                    m &= ~AA_EXEMPT;
                }
                if (m == 0) continue;                          // nothing left to guard right now
                if (sb.length() > 0) sb.append(',');
                sb.append(pkg).append(':').append(m);
            }
        }
        final String want = sb.toString();
        final String have = Settings.Secure.getString(ctx.getContentResolver(), SETTING);
        if (!want.equals(have == null ? "" : have)) {
            Settings.Secure.putString(ctx.getContentResolver(), SETTING, want);
            Log.i(TAG, "published: " + (want.isEmpty() ? "(none)" : want));
        }

        final AlarmManager am = ctx.getSystemService(AlarmManager.class);
        final PendingIntent pi = PendingIntent.getBroadcast(ctx, 0,
                new Intent(ACTION_REAPPLY).setClass(ctx, PrivacyGuardReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        if (nextExpiry != Long.MAX_VALUE) {
            // +1 s so the wall clock is past the deadline when we re-read it.
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextExpiry + 1000, pi);
        }
    }

    // ---- blocked-attempt watcher ----------------------------------------------------------

    static void start(Context ctx) {
        apply(ctx);
        if (sWatching) return;
        // Runtime permission; nobody ever prompts a persistent system app, so take it ourselves.
        if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            try {
                ctx.getPackageManager().grantRuntimePermission(ctx.getPackageName(),
                        android.Manifest.permission.POST_NOTIFICATIONS, android.os.Process.myUserHandle());
            } catch (Throwable t) {
                Log.w(TAG, "POST_NOTIFICATIONS self-grant failed", t);
            }
        }
        final HandlerThread ht = new HandlerThread("privacy-guard");
        ht.start();
        sHandler = new Handler(ht.getLooper());
        sApp = ctx.getApplicationContext();
        startForegroundWatch(ctx.getApplicationContext());
        // A call-bound allowance left from before a restart: resume watching for its end.
        if (!callBoundPkgs(sApp).isEmpty()) sHandler.postDelayed(sCallPoll, CALL_POLL_MS);
        final AppOpsManager aom = ctx.getSystemService(AppOpsManager.class);
        final Context app = ctx.getApplicationContext();
        // Auto-guard new installs. PackageManager sends PACKAGE_ADDED without
        // FLAG_RECEIVER_INCLUDE_BACKGROUND, so a manifest receiver in an O+ app is silently
        // skipped (verified: the receiver only ran for an explicit `am broadcast -n`). A runtime
        // registration is exempt, and the process is persistent so it never goes away.
        final android.content.IntentFilter pkgFilter =
                new android.content.IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addDataScheme("package");
        app.registerReceiver(new PackageGuardReceiver(), pkgFilter);
        try {
            // Camera opens and successful mic starts arrive as "started"; a mic start that is
            // soft-denied is reported by audioserver as a rejected *note*, and location
            // requests are notes as well -- so watch both.
            aom.startWatchingStarted(new int[] {
                    AppOpsManager.OP_CAMERA, AppOpsManager.OP_RECORD_AUDIO },
                    (op, uid, pkg, tag, flags, result) -> onAttempt(app, op, pkg, result, flags, true));
            // The rest (media, files, contacts, ...) are noted by the providers when the app
            // actually reads; those only prompt while the app is in front (see onAttempt), so
            // a WeChat picker that finds an empty gallery gets the question, background
            // syncs do not.
            aom.startWatchingNoted(new int[] {
                    AppOpsManager.OP_CAMERA, AppOpsManager.OP_RECORD_AUDIO,
                    AppOpsManager.OP_FINE_LOCATION, AppOpsManager.OP_COARSE_LOCATION,
                    AppOpsManager.OP_READ_CONTACTS, AppOpsManager.OP_WRITE_CONTACTS,
                    AppOpsManager.OP_READ_MEDIA_IMAGES, AppOpsManager.OP_READ_MEDIA_VIDEO,
                    AppOpsManager.OP_READ_MEDIA_VISUAL_USER_SELECTED, AppOpsManager.OP_READ_MEDIA_AUDIO,
                    AppOpsManager.OP_READ_EXTERNAL_STORAGE, AppOpsManager.OP_WRITE_EXTERNAL_STORAGE,
                    AppOpsManager.OP_BLUETOOTH_SCAN, AppOpsManager.OP_BLUETOOTH_CONNECT,
                    AppOpsManager.OP_BLUETOOTH_ADVERTISE,
                    AppOpsManager.OP_READ_PHONE_STATE, AppOpsManager.OP_READ_PHONE_NUMBERS,
                    AppOpsManager.OP_READ_CALL_LOG, AppOpsManager.OP_WRITE_CALL_LOG,
                    AppOpsManager.OP_READ_SMS, AppOpsManager.OP_RECEIVE_SMS,
                    AppOpsManager.OP_SEND_SMS, AppOpsManager.OP_RECEIVE_MMS,
                    AppOpsManager.OP_READ_CALENDAR, AppOpsManager.OP_WRITE_CALENDAR,
                    AppOpsManager.OP_BODY_SENSORS, AppOpsManager.OP_ACTIVITY_RECOGNITION },
                    (op, uid, pkg, tag, flags, result) ->
                            onAttempt(app, AppOpsManager.strOpToOp(op), pkg, result, flags, false));
            sWatching = true;
        } catch (Throwable t) {
            Log.e(TAG, "cannot watch app-ops; guard still applies, no notifications", t);
        }
    }

    private static boolean isSensorBit(int bit) {
        return bit == CAMERA || bit == MIC || bit == LOCATION;
    }

    private static int bitForOp(int op) {
        switch (op) {
            case AppOpsManager.OP_CAMERA: return CAMERA;
            case AppOpsManager.OP_RECORD_AUDIO: return MIC;
            case AppOpsManager.OP_FINE_LOCATION:
            case AppOpsManager.OP_COARSE_LOCATION: return LOCATION;
            case AppOpsManager.OP_READ_CONTACTS:
            case AppOpsManager.OP_WRITE_CONTACTS: return CONTACTS;
            case AppOpsManager.OP_READ_MEDIA_IMAGES:
            case AppOpsManager.OP_READ_MEDIA_VIDEO:
            case AppOpsManager.OP_READ_MEDIA_VISUAL_USER_SELECTED: return MEDIA_VISUAL;
            case AppOpsManager.OP_READ_MEDIA_AUDIO: return MEDIA_AUDIO;
            case AppOpsManager.OP_READ_EXTERNAL_STORAGE:
            case AppOpsManager.OP_WRITE_EXTERNAL_STORAGE: return FILES;
            case AppOpsManager.OP_BLUETOOTH_SCAN:
            case AppOpsManager.OP_BLUETOOTH_CONNECT:
            case AppOpsManager.OP_BLUETOOTH_ADVERTISE: return NEARBY;
            case AppOpsManager.OP_READ_PHONE_STATE:
            case AppOpsManager.OP_READ_PHONE_NUMBERS:
            case AppOpsManager.OP_READ_CALL_LOG:
            case AppOpsManager.OP_WRITE_CALL_LOG: return PHONE;
            case AppOpsManager.OP_READ_SMS:
            case AppOpsManager.OP_RECEIVE_SMS:
            case AppOpsManager.OP_SEND_SMS:
            case AppOpsManager.OP_RECEIVE_MMS: return SMS;
            case AppOpsManager.OP_READ_CALENDAR:
            case AppOpsManager.OP_WRITE_CALENDAR: return CALENDAR;
            case AppOpsManager.OP_BODY_SENSORS:
            case AppOpsManager.OP_ACTIVITY_RECOGNITION: return SENSORS;
            default: return 0;
        }
    }

    private static void onAttempt(Context ctx, int op, String pkg, int result, int flags, boolean started) {
        if (result != AppOpsManager.MODE_IGNORED || pkg == null || !enabled()) return;
        final int bit = bitForOp(op);
        if (bit == 0 || (maskOf(ctx, pkg) & bit) == 0 || allowedUntil(ctx, pkg, bit) != 0) return;
        if (isSensorBit(bit)) {
            Log.i(TAG, "attempt " + pkg + " " + permWord(bit) + (started ? " started" : " noted")
                    + " flags=0x" + Integer.toHexString(flags));
        }
        if (justReguarded(pkg)) return;                    // the app re-checking after our mode flip
        // A permission probe: apps audit all their permissions on launch/resume with a self
        // noteOp (YouTube: location + mic + camera inside 0.6 s), none of it a use. Real camera
        // and mic use arrives *started* from cameraserver/audioserver, or noted on the app's
        // behalf by a proxy (OP_FLAG_*_PROXIED); a lone self-noted location is a real (blocked)
        // request. Hold self-noted sensor ops briefly and drop the burst when two different ones
        // land together. Calls are decided first (a call's mic and camera may land together).
        if (isSensorBit(bit) && !started && (flags & (AppOpsManager.OP_FLAG_TRUSTED_PROXIED
                | AppOpsManager.OP_FLAG_UNTRUSTED_PROXIED)) == 0) {
            synchronized (sNoted) {
                long[] ts = sNoted.get(pkg);
                if (ts == null) sNoted.put(pkg, ts = new long[3]);
                ts[sensorIndex(bit)] = SystemClock.elapsedRealtime();
            }
            sHandler.postDelayed(() -> {
                if (callAllowApplies(ctx, pkg, bit)) { decideCallAttempt(ctx, pkg, bit); return; }
                if (isProbeBurst(pkg)) { Log.i(TAG, "permission probe, quiet: " + pkg + " " + permWord(bit)); return; }
                notifyBlocked(ctx, pkg, bit);
            }, PROBE_WINDOW_MS);
            return;
        }
        // Camera / mic / location are worth a heads-up from the background (a hidden recorder is
        // the point of the guard). Everything else is noted constantly by background syncs and
        // libraries -- only ask when the app is on screen, i.e. the user just tried something.
        if (!isSensorBit(bit)) {
            final ComponentName top = sTop;
            if ((top == null || !top.getPackageName().equals(pkg)) && !pkg.equals(sPromptPkg)) return;
        }
        final long auto = autoAllowForTop(ctx, pkg, bit);
        if (auto > 0) {          // rule screen is up but the app asked before the lift landed
            Log.i(TAG, "screen rule (late) " + pkg + " " + permWord(bit));
            sHandler.post(() -> allowFor(ctx, pkg, bit, auto));
            return;
        }
        if (callAllowApplies(ctx, pkg, bit)) {
            // Decide after the call signals have had a moment to appear (the call notification
            // and the VOICE_COMMUNICATION record client follow the app-op by a few hundred ms).
            sHandler.postDelayed(() -> decideCallAttempt(ctx, pkg, bit), CALL_DECIDE_DELAY_MS);
            return;
        }
        sHandler.post(() -> notifyBlocked(ctx, pkg, bit));
    }

    /** elapsedRealtime of the last *noted* camera / mic / location op per package. */
    private static final ArrayMap<String, long[]> sNoted = new ArrayMap<>();
    private static final long PROBE_WINDOW_MS = 1200;

    private static int sensorIndex(int bit) { return bit == CAMERA ? 0 : bit == MIC ? 1 : 2; }

    private static boolean isProbeBurst(String pkg) {
        final long now = SystemClock.elapsedRealtime();
        int n = 0;
        synchronized (sNoted) {
            final long[] ts = sNoted.get(pkg);
            if (ts == null) return false;
            for (long t : ts) if (t != 0 && now - t <= 2 * PROBE_WINDOW_MS) n++;
        }
        return n >= 2;
    }

    /** Times a background "blocked" heads-up for pkg:bit went unanswered (for the back-off). */
    private static final ArrayMap<String, Integer> sIgnored = new ArrayMap<>();

    /** Foreground prompt de-dupe: the app on screen re-tries the op every few hundred ms while
     *  our dialog is up; one launch per pkg:bit per 3 s is plenty (the prompt merges the rest). */
    private static final long PROMPT_GAP_MS = 3000L;
    private static final ArrayMap<String, Long> sLastPrompted = new ArrayMap<>();

    private static void notifyBlocked(Context ctx, String pkg, int bit) {
        // "Always block" = the mute for this app + permission: never ask again for it, on screen
        // or not. (Tried "mute = background heads-ups only" on 2026-09-19 so a muted WhatsApp could
        // still prompt for a share-location tap: YouTube / Instagram then prompted on every launch
        // probe despite Always block -- a launch probe and a user tap look the same to an app-op
        // watcher. The per-screen quiet rule below is the finer tool; un-mute to get prompts back.)
        if (muted(ctx, pkg, bit)) return;
        final long now = SystemClock.elapsedRealtime();
        final String throttleKey = pkg + ":" + bit;
        final String what = permWord(bit);

        // The app is the one on screen: ask in the middle of it (PrivacyPromptActivity, dialog
        // over the app) instead of a heads-up, and BEFORE the heads-up throttle below -- that
        // throttle is stretched by the back-off, and a voice note the user is holding the button
        // for must never be swallowed because the same app poked the mic from the background an
        // hour ago (WhatsApp voice notes, 2026-09-18). A second permission a moment later (video
        // call = mic then camera) finds our own dialog on top: still the app's turn, and the
        // prompt merges it into the one already showing.
        final ComponentName top = sTop;
        String cls = null;
        if (top != null && top.getPackageName().equals(pkg)) cls = top.getClassName();
        else if (pkg.equals(sPromptPkg)) cls = sPromptCls;
        if (cls != null) {
            if (autoAllowMs(ctx, pkg, cls, bit) == SCREEN_BLOCK) {   // "always block on this screen"
                Log.i(TAG, "screen block rule: quiet " + pkg + "/" + cls + " " + what);
                return;
            }
            final Long lastPrompt = sLastPrompted.get(throttleKey);
            if (lastPrompt != null && now - lastPrompt < PROMPT_GAP_MS) return;
            try {
                ctx.startActivity(new Intent(ctx, PrivacyPromptActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                        .putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_BIT, bit)
                        .putExtra(EXTRA_CLS, cls));
                Log.i(TAG, "blocked " + pkg + " " + what + " (foreground prompt)");
                sPromptPkg = pkg;
                sPromptCls = cls;
                sLastPrompted.put(throttleKey, now);
                sLastNotified.put(throttleKey, now);
                synchronized (sIgnored) { sIgnored.remove(throttleKey); }   // the app is in front again
                return;
            } catch (Exception e) {
                Log.w(TAG, "foreground prompt unavailable, falling back to the heads-up: " + e);
            }
        }

        long gap = (bit == LOCATION) ? NOTIFY_GAP_LOCATION_MS
                : isSensorBit(bit) ? NOTIFY_GAP_MS : NOTIFY_GAP_QUIET_MS;
        // Back-off: each ignored background heads-up for this app + permission stretches the
        // next gap 5x (1 min -> 5 -> 25 -> ~2 h -> 6 h cap). Google's hotword/location polling
        // would otherwise nag once a minute forever. Reset by an answer or the app coming to the
        // front (the dialog path above), so a real user action is never delayed by earlier noise.
        if (backoff()) {
            final Integer n;
            synchronized (sIgnored) { n = sIgnored.get(throttleKey); }
            for (int i = 0; n != null && i < n && gap < NOTIFY_GAP_LOCATION_MS; i++) gap *= 5;
            gap = Math.min(gap, NOTIFY_GAP_LOCATION_MS);
        }
        final Long last = sLastNotified.get(throttleKey);
        if (last != null && now - last < gap) return;
        // Location, contacts, photos, files... while the app is in a call (its call screen probes
        // them all as it opens): still blocked, but silently -- a dialog on top of a call is the
        // last thing anyone wants, and the app can ask again after it. (Past the throttle, so
        // this costs one service walk per 5 min per permission at most.)
        if (bit != MIC && bit != CAMERA && callAware() && CallDetect.of(ctx, pkg) != CallDetect.NONE) {
            Log.i(TAG, "during call blocked quietly: " + pkg + " " + permWord(bit));
            return;
        }
        sLastNotified.put(throttleKey, now);

        final String sees = bit == CAMERA ? "a disabled camera." : bit == MIC ? "silence."
                : bit == LOCATION ? "no fix." : bit == MEDIA_VISUAL ? "an empty gallery."
                : bit == FILES ? "no files." : bit == CONTACTS ? "no contacts." : "nothing.";
        final String label = label(ctx, pkg);
        Log.i(TAG, "blocked " + pkg + " " + what);

        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(askChannel());
        // Deep-link to THIS app's Privacy Guard editor. Per-package request code so each app's
        // notification keeps its own extras (request code 0 for all + FLAG_UPDATE_CURRENT would
        // make every notification open whichever app fired last).
        final PendingIntent open = PendingIntent.getActivity(ctx, (pkg + ":open").hashCode(),
                new Intent(ctx, SettingsActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA_PKG, pkg)
                        .putExtra(EXTRA_OPEN_EDITOR, true),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final Notification.Builder nb = new Notification.Builder(ctx, CHANNEL_ASK)
                .setSmallIcon(R.drawable.ic_privacy)
                .setContentTitle(permGlyph(bit) + " " + label + " tried to use the " + what)
                .setContentText("Blocked; it sees " + sees)
                .setStyle(new Notification.BigTextStyle().bigText("Blocked; it sees " + sees
                        + " Allow its " + what + " for a few minutes, or Always block to stop asking."))
                .setContentIntent(open)
                .setAutoCancel(true);
        // No setOnlyAlertOnce: the 60 s throttle above already limits repeats, and a fresh block
        // after that should pop the heads-up again rather than silently refresh the shade entry.
        // Per-permission, per-duration allow actions (5 / 10 min) for THIS permission only,
        // then "Always block" (mute this app's alerts; the guard itself never changes). Short labels:
        // the action row shrinks the widest buttons first, so "5 min" / "10 min" keep the third whole.
        for (long ms : ALLOW_CHOICES_MS) {
            final int mins = (int) (ms / 60000);
            final PendingIntent allow = PendingIntent.getBroadcast(ctx,
                    (pkg + ":" + bit + ":" + mins).hashCode(),
                    new Intent(ACTION_ALLOW).setClass(ctx, PrivacyGuardReceiver.class)
                            .putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_BIT, bit).putExtra(EXTRA_MS, ms),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(new Notification.Action.Builder(null, mins + " min", allow).build());
        }
        nb.addAction(new Notification.Action.Builder(null, "Always block", muteIntent(ctx, pkg, bit)).build());
        nm.notify(pkg, 0, nb.build());
        synchronized (sIgnored) {                          // counts as ignored until answered
            final Integer n = sIgnored.get(throttleKey);
            sIgnored.put(throttleKey, n == null ? 1 : n + 1);
        }
    }

    /** "Always block": mute this app's alerts for this permission (PrivacyGuardReceiver -> setMuted). */
    private static PendingIntent muteIntent(Context ctx, String pkg, int bit) {
        return PendingIntent.getBroadcast(ctx, (pkg + ":mute:" + bit).hashCode(),
                new Intent(ACTION_MUTE).setClass(ctx, PrivacyGuardReceiver.class)
                        .putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_BIT, bit),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static NotificationChannel askChannel() {
        final NotificationChannel ch = new NotificationChannel(CHANNEL_ASK, "Privacy guard prompts",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Pops up when a guarded app is blocked, with the timed-allow buttons");
        return ch;
    }

    private static void cancelNotification(Context ctx, String pkg) {
        ctx.getSystemService(NotificationManager.class).cancel(pkg, 0);
    }

    /** Deep-link PendingIntent to this app's Privacy Guard editor (shared by all our notifications). */
    private static PendingIntent editorIntent(Context ctx, String pkg) {
        return PendingIntent.getActivity(ctx, (pkg + ":open").hashCode(),
                new Intent(ctx, SettingsActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_OPEN_EDITOR, true),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Ongoing "X has <perm> — m:ss left" notification with Extend +5/+10 while an allowance runs. */
    private static void notifyAllowed(Context ctx, String pkg, int bit) {
        final long until = prefs(ctx).getLong(untilKey(pkg, bit), 0);
        if (until <= System.currentTimeMillis()) return;
        final String what = permWord(bit);
        final String label = label(ctx, pkg);
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Privacy guard",
                NotificationManager.IMPORTANCE_DEFAULT));
        final Notification.Builder nb = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_privacy)
                .setContentTitle(permGlyph(bit) + " " + label + " has " + what + " access")
                .setContentText("Ends on its own — tap Extend to add more time.")
                .setContentIntent(editorIntent(ctx, pkg))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(until);
        for (long ms : ALLOW_CHOICES_MS) {
            final int mins = (int) (ms / 60000);
            final PendingIntent ext = PendingIntent.getBroadcast(ctx,
                    (pkg + ":ext:" + bit + ":" + mins).hashCode(),
                    new Intent(ACTION_EXTEND).setClass(ctx, PrivacyGuardReceiver.class)
                            .putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_BIT, bit).putExtra(EXTRA_MS, ms),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(new Notification.Action.Builder(null, "+" + mins + " min", ext).build());
        }
        nm.notify(pkg, bit, nb.build());
    }

    /** When an allowance ends: offer to renew with the same 5/10 buttons, or Always block (the "re-prompt on expiry"). */
    private static void notifyExpiredPrompt(Context ctx, String pkg, int bit) {
        final String what = permWord(bit);
        final String label = label(ctx, pkg);
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(askChannel());
        final Notification.Builder nb = new Notification.Builder(ctx, CHANNEL_ASK)
                .setSmallIcon(R.drawable.ic_privacy)
                .setContentTitle(permGlyph(bit) + " " + label + "’s " + what + " access ended")
                .setContentText("Guarded again. Allow more, or Always block to stop asking.")
                .setContentIntent(editorIntent(ctx, pkg))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true);
        for (long ms : ALLOW_CHOICES_MS) {
            final int mins = (int) (ms / 60000);
            final PendingIntent allow = PendingIntent.getBroadcast(ctx,
                    (pkg + ":again:" + bit + ":" + mins).hashCode(),
                    new Intent(ACTION_ALLOW).setClass(ctx, PrivacyGuardReceiver.class)
                            .putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_BIT, bit).putExtra(EXTRA_MS, ms),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(new Notification.Action.Builder(null, mins + " min", allow).build());
        }
        nb.addAction(new Notification.Action.Builder(null, "Always block", muteIntent(ctx, pkg, bit)).build());
        nm.notify(pkg, bit, nb.build());
    }

    static String label(Context ctx, String pkg) {
        try {
            final PackageManager pm = ctx.getPackageManager();
            final ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            final CharSequence l = pm.getApplicationLabel(ai);
            return l == null ? pkg : l.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static boolean installed(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
