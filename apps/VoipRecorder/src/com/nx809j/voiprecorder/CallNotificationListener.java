package com.nx809j.voiprecorder;

import android.content.Intent;
import android.media.AudioManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.concurrent.Executors;

/**
 * "Hide notification" mode only. A NotificationListenerService stays bound by the system
 * with NO notification of its own, so it can host the audio-mode listener without any
 * persistent foreground-service notification. When a VoIP call starts it fires a one-shot
 * recording session (CallMonitorService in RECORD role), which foregrounds — and thus shows
 * a notification — ONLY for the duration of the call.
 *
 * We don't read notification content; the listener is used purely as a permission-gated
 * always-alive host. That's why enabling this mode needs a one-time "Notification access" grant.
 */
public class CallNotificationListener extends NotificationListenerService {
    private static final String TAG = "VoipRecorder";
    private AudioManager am;
    private Prefs prefs;
    private AudioManager.OnModeChangedListener modeListener;
    private volatile boolean sessionActive;

    @Override public void onListenerConnected() {
        prefs = new Prefs(this);
        am = getSystemService(AudioManager.class);
        if (modeListener == null) {
            modeListener = this::onMode;
            am.addOnModeChangedListener(Executors.newSingleThreadExecutor(), modeListener);
            onMode(am.getMode());
        }
        Log.i(TAG, "NLS connected");
    }

    @Override public void onListenerDisconnected() {
        if (modeListener != null) {
            try { am.removeOnModeChangedListener(modeListener); } catch (Exception ignored) {}
            modeListener = null;
        }
    }

    private synchronized void onMode(int mode) {
        // Act only in hide-notification mode and when recording is enabled+consented.
        if (prefs == null || !prefs.hideNotification()
                || !prefs.isEnabled() || !prefs.hasConsent()) return;
        boolean inCall = (mode == AudioManager.MODE_IN_COMMUNICATION);
        if (inCall && !sessionActive) {
            String pkg = Util.foregroundApp(this);
            if (!Util.shouldRecord(prefs, pkg)) return;
            sessionActive = true;
            startForegroundService(new Intent(this, CallMonitorService.class)
                    .setAction(CallMonitorService.ACTION_RECORD));
        } else if (!inCall && sessionActive) {
            // the RECORD session self-terminates on call end; just reset our latch
            sessionActive = false;
        }
    }

    // required overrides — unused (we don't inspect notifications)
    @Override public void onNotificationPosted(StatusBarNotification sbn) {}
    @Override public void onNotificationRemoved(StatusBarNotification sbn) {}
}
