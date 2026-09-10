package com.nx809j.voiprecorder;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Watches the RECORD_AUDIO app-op going active/inactive for OTHER apps and starts a recording
 * session when an allow-listed VoIP app opens the mic during a call. This is a pure event
 * callback -- it fires only on a mic start/stop, so the process does no work while idle -- and
 * it hands us the exact package, so the old 15 s UsageStats foreground poll is gone.
 *
 * A voice note or the camera also makes RECORD_AUDIO active, so we additionally require the audio
 * mode to be MODE_IN_COMMUNICATION (WebRTC calls set it; voice notes do not). Watching other uids'
 * ops needs WATCH_APPOPS (signature|privileged; allow-listed).
 */
final class CallDetector {
    private static final String TAG = "VoipRecorder";
    private static CallDetector sInstance;

    static synchronized CallDetector get(Context ctx) {
        if (sInstance == null) sInstance = new CallDetector(ctx.getApplicationContext());
        return sInstance;
    }

    private final Context ctx;
    private final Prefs prefs;
    private final AppOpsManager appOps;
    private final AudioManager am;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AppOpsManager.OnOpActiveChangedListener listener;
    private volatile String recordingPkg;   // non-null while a session is (about to be) running
    private volatile String micActivePkg;   // a VoIP app currently holding the mic (candidate)
    // The app can flip to a communication mode/source a little after the mic opens, so re-check.
    private static final int[] RETRY_MS = { 500, 1000, 1200, 1500, 2000, 2500 };

    private CallDetector(Context ctx) {
        this.ctx = ctx;
        this.prefs = new Prefs(ctx);
        this.appOps = ctx.getSystemService(AppOpsManager.class);
        this.am = ctx.getSystemService(AudioManager.class);
    }

    /** Arm the watcher when the user has enabled + consented, disarm otherwise. */
    synchronized void sync() {
        final boolean want = prefs.isEnabled() && prefs.hasConsent();
        if (want && listener == null) {
            listener = (op, uid, pkg, active) -> onMic(pkg, active);
            appOps.startWatchingActive(new String[]{AppOpsManager.OPSTR_RECORD_AUDIO},
                    executor, listener);
            Log.i(TAG, "mic watcher armed");
        } else if (!want && listener != null) {
            appOps.stopWatchingActive(listener);
            listener = null;
            Log.i(TAG, "mic watcher disarmed");
        }
    }

    private void onMic(String pkg, boolean active) {
        if (pkg == null || pkg.equals(ctx.getPackageName())) return;   // ignore our own capture
        if (active) {
            if (recordingPkg != null) return;                           // already handling a call
            if (isTelephony(pkg)) return;                               // dialer mic-op = SIM call
            final Util.Policy policy = Util.policyFor(prefs, pkg);
            if (policy == Util.Policy.IGNORE) return;
            Log.i(TAG, "mic active: " + pkg + " -> watching for a call (" + policy + ")");
            micActivePkg = pkg;
            scheduleAttempt(pkg, 0);
        } else {
            if (pkg.equals(recordingPkg)) {
                recordingPkg = null;   // call's mic released; the session self-stops
            }
            if (pkg.equals(micActivePkg)) {
                micActivePkg = null;   // candidate released the mic before we confirmed a call
            }
            if (pkg.equals(askedPkg)) {
                askedPkg = null;       // call over: the question may be asked again next call
                nm().cancel(askNotifId(pkg));
            }
        }
    }

    /**
     * Start recording pkg's call right now (the user answered "Always"/"This call" on the ask
     * notification, or "Always" applies to the call that is still up). False if there is no call.
     */
    synchronized boolean recordNow(String pkg) {
        if (recordingPkg != null) return pkg.equals(recordingPkg);
        if (!prefs.isEnabled() || !prefs.hasConsent()) return false;
        if (!isCall() || !micHeldBy(pkg)) return false;
        startSession(pkg);
        return true;
    }

    private void startSession(String pkg) {
        recordingPkg = pkg;
        micActivePkg = null;
        ctx.startForegroundService(new Intent(ctx, CallMonitorService.class)
                .setAction(CallMonitorService.ACTION_RECORD)
                .putExtra(CallMonitorService.EXTRA_PKG, pkg));
        Log.i(TAG, "call confirmed on mic (" + pkg + ") -> recording");
    }

    /** Is pkg (still) an active mic client? Used when the user answers the ask notification. */
    private boolean micHeldBy(String pkg) {
        try {
            int uid = ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
            for (android.media.AudioRecordingConfiguration c : am.getActiveRecordingConfigurations()) {
                if (c.getClientUid() == uid) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    // ---- "Record calls from X?" (unknown app, ask mode) ----
    private static final String CH_ASK = "voiprec_ask";
    private volatile String askedPkg;

    static int askNotifId(String pkg) { return 1000 + (pkg.hashCode() & 0xffff); }

    private NotificationManager nm() { return ctx.getSystemService(NotificationManager.class); }

    private void ask(String pkg) {
        if (pkg.equals(askedPkg)) return;     // one question per call
        askedPkg = pkg;
        NotificationManager nm = nm();
        NotificationChannel c = new NotificationChannel(CH_ASK, "Record calls from new apps?",
                NotificationManager.IMPORTANCE_DEFAULT);
        c.setShowBadge(false);
        nm.createNotificationChannel(c);
        String label = Util.appLabel(ctx, pkg);
        Notification.Builder b = new Notification.Builder(ctx, CH_ASK)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Record calls from " + label + "?")
                .setContentText("Not recording this call. Choose once; change it later in "
                        + "RedMagic Control > Privacy.")
                .setStyle(new Notification.BigTextStyle().bigText("Not recording this call. "
                        + "\u201cAlways\u201d also starts recording now; change the choice later in "
                        + "RedMagic Control > Privacy > VoIP call recorder."))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .addAction(action(pkg, DecisionReceiver.ALWAYS, "Always"))
                .addAction(action(pkg, DecisionReceiver.ONCE, "This call"))
                .addAction(action(pkg, DecisionReceiver.NEVER, "Never"));
        nm.notify(askNotifId(pkg), b.build());
        Log.i(TAG, "asked about " + pkg);
    }

    private Notification.Action action(String pkg, int what, String label) {
        Intent i = new Intent(ctx, DecisionReceiver.class)
                .setAction(DecisionReceiver.ACTION)
                .putExtra(DecisionReceiver.EXTRA_PKG, pkg)
                .putExtra(DecisionReceiver.EXTRA_WHAT, what);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, what * 100000 + (pkg.hashCode() & 0xffff),
                i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(0, label, pi).build();
    }

    private void scheduleAttempt(String pkg, int idx) {
        main.postDelayed(() -> tryStart(pkg, idx), RETRY_MS[idx]);
    }

    private synchronized void tryStart(String pkg, int idx) {
        if (recordingPkg != null) return;
        if (!pkg.equals(micActivePkg)) return;                 // mic released / another app took over
        if (!prefs.isEnabled() || !prefs.hasConsent()) return;
        if (isCall()) {
            if (Util.policyFor(prefs, pkg) == Util.Policy.ASK) {
                micActivePkg = null;
                ask(pkg);
            } else {
                startSession(pkg);
            }
            return;
        }
        if (idx + 1 < RETRY_MS.length) {
            scheduleAttempt(pkg, idx + 1);                     // app may enter comm mode shortly
        } else {
            Log.i(TAG, "mic active for " + pkg + " but no call signal; not recording");
        }
    }

    /**
     * A real VoIP call = a communication audio mode, or an active VOICE_COMMUNICATION recording.
     * MODE_IN_CALL / MODE_RINGTONE are telephony (SIM/VoLTE) calls: Telecom holds the mic
     * app-op on behalf of the dialer while one rings or is active, but that audio lives in the
     * modem and never crosses AudioFlinger, so a session would only ever produce an empty file
     * (the "com.android.dialer 0 KB" rows). Skip those outright.
     */
    private boolean isCall() {
        final int m = am.getMode();
        if (m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_RINGTONE) return false;
        if (m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_COMMUNICATION_REDIRECT) {
            return true;
        }
        try {
            for (AudioRecordingConfiguration c : am.getActiveRecordingConfigurations()) {
                if (c.getClientAudioSource() == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                    return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** The default dialer and the telephony stack: their mic use is a cellular call, not VoIP. */
    private boolean isTelephony(String pkg) {
        if ("com.android.phone".equals(pkg) || "com.android.server.telecom".equals(pkg)) return true;
        try {
            String dialer = ctx.getSystemService(android.telecom.TelecomManager.class)
                    .getDefaultDialerPackage();
            if (pkg.equals(dialer)) return true;
        } catch (Exception ignored) { }
        return "com.android.dialer".equals(pkg) || "com.google.android.dialer".equals(pkg);
    }

    /** Called by the session when it ends, so a later call re-triggers. */
    void sessionEnded() { recordingPkg = null; }
}
