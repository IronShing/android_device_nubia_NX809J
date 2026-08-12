package com.nx809j.voiprecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.Executors;

/**
 * Two roles, selected by intent action:
 *
 *  • MONITOR (default start) — the "notification-visible" mode. Runs persistently as a
 *    foreground service with a silent ongoing notification, watches the audio mode, and
 *    records inline when a VoIP call starts.
 *
 *  • RECORD  (ACTION_RECORD) — a single recording session started by CallNotificationListener
 *    in the "hide notification" mode. Foregrounds ONLY for the duration of the call (so the
 *    notification appears only while recording), then stops itself when the call ends.
 */
public class CallMonitorService extends Service {
    private static final String TAG = "VoipRecorder";
    private static final String CH = "voiprec";
    private static final int NOTIF = 42;
    static final String ACTION_RECORD = "com.nx809j.voiprecorder.RECORD";
    private static final int FGS_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;

    private AudioManager am;
    private Prefs prefs;
    private CallRecorder recorder;
    private volatile boolean recording;
    private boolean started;
    private boolean sessionMode;
    private AudioManager.OnModeChangedListener modeListener;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        am = getSystemService(AudioManager.class);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        boolean wantSession = intent != null && ACTION_RECORD.equals(intent.getAction());
        if (!started) {
            started = true;
            sessionMode = wantSession;
            if (sessionMode) startSession(); else startMonitor();
        }
        return sessionMode ? START_NOT_STICKY : START_STICKY;
    }

    @Override public void onDestroy() {
        try { am.removeOnModeChangedListener(modeListener); } catch (Exception ignored) {}
        if (recording) stopRecording();
        super.onDestroy();
    }

    // ---- MONITOR role (default / notification-visible mode) --------------------

    private void startMonitor() {
        startForeground(NOTIF, buildNotif(false), FGS_TYPES);
        modeListener = this::onModeMonitor;
        am.addOnModeChangedListener(Executors.newSingleThreadExecutor(), modeListener);
        onModeMonitor(am.getMode());        // catch a call already in progress
        Log.i(TAG, "monitor up");
    }

    private synchronized void onModeMonitor(int mode) {
        boolean inCall = (mode == AudioManager.MODE_IN_COMMUNICATION);
        if (inCall && !recording) {
            if (!prefs.isEnabled() || !prefs.hasConsent()) return;
            String pkg = Util.foregroundApp(this);
            if (!Util.shouldRecord(prefs, pkg)) return;
            startRecording(pkg == null ? "voip" : pkg);
            notif().notify(NOTIF, buildNotif(true));
        } else if (!inCall && recording) {
            stopRecording();
            notif().notify(NOTIF, buildNotif(false));
        }
    }

    // ---- RECORD role (single session, "hide notification" mode) ---------------

    private void startSession() {
        startForeground(NOTIF, buildNotif(true), FGS_TYPES);
        String pkg = Util.foregroundApp(this);
        startRecording(pkg == null ? "voip" : pkg);
        // self-terminate when the call ends
        modeListener = mode -> { if (mode != AudioManager.MODE_IN_COMMUNICATION) endSession(); };
        am.addOnModeChangedListener(Executors.newSingleThreadExecutor(), modeListener);
        Log.i(TAG, "record session up");
    }

    private synchronized void endSession() {
        if (recording) stopRecording();
        try { am.removeOnModeChangedListener(modeListener); } catch (Exception ignored) {}
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ---- shared recording ------------------------------------------------------

    private void startRecording(String tag) {
        recorder = new CallRecorder(this);
        if (recorder.start(tag)) {
            recording = true;
        } else {
            recorder = null;
            Log.e(TAG, "recorder failed to start");
        }
    }

    private void stopRecording() {
        recording = false;
        if (recorder != null) {
            recorder.stop();
            Log.i(TAG, "saved: " + recorder.getOutputFile());
            recorder = null;
        }
    }

    // ---- notification ----------------------------------------------------------

    private NotificationManager notif() { return getSystemService(NotificationManager.class); }

    private void createChannel() {
        NotificationChannel c = new NotificationChannel(CH, "Call recorder",
                NotificationManager.IMPORTANCE_LOW);   // OS forces FGS notifs to >= LOW anyway
        c.setShowBadge(false);
        notif().createNotificationChannel(c);
    }

    private Notification buildNotif(boolean active) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, RecordingsActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(active
                        ? android.R.drawable.ic_btn_speak_now
                        : android.R.drawable.stat_notify_voicemail)
                .setContentTitle(active ? "● Recording call" : "Call recorder active")
                .setContentText(active
                        ? "Recording this VoIP call"
                        : "Auto-records allow-listed VoIP calls")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}
