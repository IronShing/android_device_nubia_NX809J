package com.nubia.rmcontrol;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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

    static final int CAMERA = 1;
    static final int MIC = 2;
    static final int LOCATION = 4;
    static final int CONTACTS = 8;
    static final int MEDIA_VISUAL = 16;   // photos & videos
    static final int MEDIA_AUDIO = 32;    // audio & music
    static final int FILES = 64;
    static final int NEARBY = 128;        // nearby devices (bluetooth)
    static final int ALL = CAMERA | MIC | LOCATION | CONTACTS | MEDIA_VISUAL | MEDIA_AUDIO | FILES | NEARBY;

    static final long ALLOW_MS = 10 * 60 * 1000L;

    private static final String PREFS = "privacy";
    private static final String KEY_MASK = "mask:";      // + pkg -> int
    private static final String KEY_UNTIL = "until:";    // + pkg -> long wall-clock ms
    private static final String KEY_MUTE = "mute:";      // + pkg -> boolean: no "blocked" alerts
    private static final String KEY_AA = "aa:";          // + pkg -> boolean: lift mic/loc during Android Auto

    /** Ops lifted for an AA-allowed package while Android Auto (car mode) is connected. */
    static final int AA_EXEMPT = MIC | LOCATION;
    /** Set by CarModeWatcher from UiModeManager ENTER/EXIT_CAR_MODE (Android Auto projection). */
    private static volatile boolean sAaConnected;

    static final String ACTION_ALLOW = "com.nubia.rmcontrol.PRIVACY_ALLOW";
    static final String ACTION_REAPPLY = "com.nubia.rmcontrol.PRIVACY_REAPPLY";
    static final String EXTRA_PKG = "pkg";
    private static final String CHANNEL = "privacy_guard";

    /** Minimum gap between notifications for the same package. */
    private static final long NOTIFY_GAP_MS = 60 * 1000L;
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
        if (mask == 0) ed.remove(KEY_MASK + pkg).remove(KEY_UNTIL + pkg).remove(KEY_MUTE + pkg).remove(KEY_AA + pkg);
        else ed.putInt(KEY_MASK + pkg, mask & ALL);
        ed.apply();
        apply(ctx);
    }

    static boolean autoEnabled() {
        return Prop.getBool(PROP_AUTO, false);
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
        setMask(ctx, pkg, ALL);
        if (!enabled()) setEnabled(ctx, true);
    }

    /** Wall-clock deadline until which the package is temporarily un-guarded, or 0. */
    /**
     * Per-app alert mute. The guard still applies -- this only silences the "<app> tried to use
     * the …" notification for apps that poll constantly (a chat app trying the mic on every
     * call, a launcher polling location) where the alert is noise. The "Allow for 10 min"
     * action is still reachable from the app's row in the Privacy tab.
     */
    static boolean muted(Context ctx, String pkg) {
        return prefs(ctx).getBoolean(KEY_MUTE + pkg, false);
    }

    static void setMuted(Context ctx, String pkg, boolean on) {
        final SharedPreferences.Editor ed = prefs(ctx).edit();
        if (on) ed.putBoolean(KEY_MUTE + pkg, true); else ed.remove(KEY_MUTE + pkg);
        ed.apply();
        if (on) cancelNotification(ctx, pkg);   // take down one already showing
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

    static long allowedUntil(Context ctx, String pkg) {
        final long t = prefs(ctx).getLong(KEY_UNTIL + pkg, 0);
        return t > System.currentTimeMillis() ? t : 0;
    }

    static void allowFor(Context ctx, String pkg, long ms) {
        prefs(ctx).edit().putLong(KEY_UNTIL + pkg, System.currentTimeMillis() + ms).apply();
        Log.i(TAG, "allow " + pkg + " for " + (ms / 1000) + " s");
        apply(ctx);
        cancelNotification(ctx, pkg);
    }

    static void revokeAllow(Context ctx, String pkg) {
        prefs(ctx).edit().remove(KEY_UNTIL + pkg).apply();
        apply(ctx);
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
                final long until = p.getLong(KEY_UNTIL + r.getKey(), 0);
                if (until > now) {
                    nextExpiry = Math.min(nextExpiry, until);
                    continue;                                  // temporarily allowed
                }
                int m = r.getValue();
                // Android Auto: lift mic/location for opted-in apps while AA (car mode) is connected.
                if (sAaConnected && p.getBoolean(KEY_AA + r.getKey(), false)) {
                    m &= ~AA_EXEMPT;
                    if (m == 0) continue;                      // nothing left to guard right now
                }
                if (sb.length() > 0) sb.append(',');
                sb.append(r.getKey()).append(':').append(m);
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
                    (op, uid, pkg, tag, flags, result) -> onAttempt(app, op, pkg, result));
            aom.startWatchingNoted(new int[] {
                    AppOpsManager.OP_CAMERA, AppOpsManager.OP_RECORD_AUDIO,
                    AppOpsManager.OP_FINE_LOCATION, AppOpsManager.OP_COARSE_LOCATION },
                    (op, uid, pkg, tag, flags, result) ->
                            onAttempt(app, AppOpsManager.strOpToOp(op), pkg, result));
            sWatching = true;
        } catch (Throwable t) {
            Log.e(TAG, "cannot watch app-ops; guard still applies, no notifications", t);
        }
    }

    private static int bitForOp(int op) {
        switch (op) {
            case AppOpsManager.OP_CAMERA: return CAMERA;
            case AppOpsManager.OP_RECORD_AUDIO: return MIC;
            case AppOpsManager.OP_FINE_LOCATION:
            case AppOpsManager.OP_COARSE_LOCATION: return LOCATION;
            default: return 0;
        }
    }

    private static void onAttempt(Context ctx, int op, String pkg, int result) {
        if (result != AppOpsManager.MODE_IGNORED || pkg == null || !enabled()) return;
        final int bit = bitForOp(op);
        if (bit == 0 || (maskOf(ctx, pkg) & bit) == 0 || allowedUntil(ctx, pkg) != 0) return;
        if (muted(ctx, pkg)) return;
        sHandler.post(() -> notifyBlocked(ctx, pkg, bit));
    }

    private static void notifyBlocked(Context ctx, String pkg, int bit) {
        final long now = SystemClock.elapsedRealtime();
        final long gap = (bit == LOCATION) ? NOTIFY_GAP_LOCATION_MS : NOTIFY_GAP_MS;
        final String throttleKey = pkg + ":" + bit;
        final Long last = sLastNotified.get(throttleKey);
        if (last != null && now - last < gap) return;
        sLastNotified.put(throttleKey, now);

        final String what = bit == CAMERA ? "camera" : bit == MIC ? "microphone" : "location";
        final String label = label(ctx, pkg);
        Log.i(TAG, "blocked " + pkg + " " + what);

        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Privacy guard",
                NotificationManager.IMPORTANCE_DEFAULT));
        final PendingIntent allow = PendingIntent.getBroadcast(ctx, pkg.hashCode(),
                new Intent(ACTION_ALLOW).setClass(ctx, PrivacyGuardReceiver.class)
                        .putExtra(EXTRA_PKG, pkg),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, SettingsActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_privacy)
                .setContentTitle(label + " tried to use the " + what)
                .setContentText("Blocked by Privacy guard. The app sees "
                        + (bit == CAMERA ? "a disabled camera." : bit == MIC ? "silence." : "no fix."))
                .setStyle(new Notification.BigTextStyle().bigText("Blocked by Privacy guard; "
                        + label + " still thinks it has permission. Allow it for 10 minutes if "
                        + "you need it right now, or change its rules in RedMagic Control."))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Allow for 10 min", allow).build())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        nm.notify(pkg, 0, n);
    }

    private static void cancelNotification(Context ctx, String pkg) {
        ctx.getSystemService(NotificationManager.class).cancel(pkg, 0);
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
