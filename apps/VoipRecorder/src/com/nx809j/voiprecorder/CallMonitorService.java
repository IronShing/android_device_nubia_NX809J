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
 * One recording session, started by CallDetector when an allow-listed VoIP call begins. It
 * foregrounds as a MICROPHONE service (so the mic is not silenced and the OS shows that a call
 * is being recorded) ONLY for the duration of the call, records both sides through CallRecorder,
 * and stops itself when the audio mode leaves communication. Nothing here runs between calls --
 * detection lives in CallDetector on the persistent process.
 */
public class CallMonitorService extends Service {
    private static final String TAG = "VoipRecorder";
    private static final String CH = "voiprec";
    private static final int NOTIF = 42;
    static final String ACTION_RECORD = "com.nx809j.voiprecorder.RECORD";
    static final String EXTRA_PKG = "pkg";
    private static final int FGS_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;

    private AudioManager am;
    private CallRecorder recorder;
    private volatile boolean recording;
    private boolean started;
    private AudioManager.OnModeChangedListener modeListener;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        am = getSystemService(AudioManager.class);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!started) {
            started = true;
            startForeground(NOTIF, buildNotif(), FGS_TYPES);
            String pkg = intent != null ? intent.getStringExtra(EXTRA_PKG) : null;
            appLabel = pkg == null ? "VoIP" : Util.appLabel(this, pkg);
            startRecording(appLabel);
            if (pkg != null) peekContact(pkg);
            // Self-terminate when the call ends. The mode listener is event-driven and only
            // registered for the length of the call, so it costs nothing between calls.
            modeListener = mode -> { if (mode != AudioManager.MODE_IN_COMMUNICATION) endSession(); };
            am.addOnModeChangedListener(Executors.newSingleThreadExecutor(), modeListener);
            if (am.getMode() != AudioManager.MODE_IN_COMMUNICATION) endSession();  // ended already
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        try { if (modeListener != null) am.removeOnModeChangedListener(modeListener); }
        catch (Exception ignored) {}
        if (recording) stopRecording();
        CallDetector.get(this).sessionEnded();
        super.onDestroy();
    }

    private synchronized void endSession() {
        if (!started) return;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

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
            java.io.File out = recorder.getOutputFile();
            // Name the file "<App> - <Contact> (stamp).wav" now that the contact is known.
            String c = contact;
            if (c != null && out != null && out.exists()) {
                java.io.File named = new java.io.File(out.getParentFile(),
                        CallRecorder.fileName(appLabel + " - " + c, out.getName()));
                if (out.renameTo(named)) out = named;
            }
            Log.i(TAG, "saved: " + out);
            recorder = null;
        }
    }

    private String appLabel;
    private volatile String contact;

    /**
     * The app's call notification carries the contact's name; it can lag the mic by a few
     * seconds, so look twice. Off the main thread: the peek blocks on a listener rebind.
     */
    private void peekContact(final String pkg) {
        Executors.newSingleThreadExecutor().execute(() -> {
            for (int delay : new int[] { 2500, 6000 }) {
                try { Thread.sleep(delay); } catch (InterruptedException e) { return; }
                if (!recording) return;
                String c = CallNotifPeek.peekTitle(this, pkg, 2000);
                if (c != null && !c.isEmpty()) {
                    contact = Util.fileSafe(c);
                    Log.i(TAG, "call with: " + contact);
                    notif().notify(NOTIF, buildNotif());
                    return;
                }
            }
        });
    }

    private NotificationManager notif() { return getSystemService(NotificationManager.class); }

    private void createChannel() {
        NotificationChannel c = new NotificationChannel(CH, "Call recorder",
                NotificationManager.IMPORTANCE_LOW);
        c.setShowBadge(false);
        notif().createNotificationChannel(c);
    }

    private Notification buildNotif() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, RecordingsActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("● Recording call")
                .setContentText(contact != null ? appLabel + " call with " + contact
                        : "Recording this " + (appLabel == null ? "VoIP" : appLabel) + " call")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}
