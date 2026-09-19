package com.nubia.rmcontrol;

import android.app.ActivityManager;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.List;

/**
 * Is this package in a call right now, and is it a video call?
 *
 * Privacy Guard cannot tell a WhatsApp call from a WhatsApp voice note by the app-op alone: both
 * are RECORD_AUDIO. The platform, however, knows about calls, and every signal below is
 * something an app cannot fake cheaply for a mic grab it wants to hide:
 *  1. its ongoing/incoming call notification (Notification.CallStyle, CATEGORY_CALL) — required
 *     by Android for a call in the foreground; CallStyle also says whether the call is video;
 *  2. a running foreground service whose declared type includes phoneCall (A14+ requires the
 *     type for a call's FGS);
 *  3. an active audio record with source VOICE_COMMUNICATION (the VoIP path: AEC/NS, comms
 *     routing, the earpiece), as opposed to MIC / VOICE_RECOGNITION / CAMCORDER for notes,
 *     assistants and video recording. The record is still listed while the guard silences it.
 *
 * We run as the system uid, so getRunningServices returns every process' services and the
 * recording configurations carry the client uid (both are anonymised for ordinary apps).
 */
final class CallDetect {
    private static final String TAG = "RMControl.CallDetect";

    static final int NONE = 0;
    static final int VOICE = 1;
    static final int VIDEO = 2;

    private CallDetect() { }

    static String name(int state) {
        return state == VIDEO ? "video call" : state == VOICE ? "voice call" : "no call";
    }

    /** Strongest call state any signal reports for the package. */
    static int of(Context ctx, String pkg) {
        int s = NONE;
        s = Math.max(s, fromNotification(pkg));
        if (s == VIDEO) return s;
        s = Math.max(s, fromForegroundService(ctx, pkg));
        if (s == VIDEO) return s;
        s = Math.max(s, fromRecording(ctx, pkg));
        return s;
    }

    /** CallStyle / CATEGORY_CALL notification from the package (needs our listener bound). */
    private static int fromNotification(String pkg) {
        final int s = CenterNotifListener.callState(pkg);
        return s < 0 ? NONE : s;
    }

    /** A foreground service of the package whose manifest type includes phoneCall. The service
     *  info only carries the declared types (apps declare phoneCall|microphone|camera together),
     *  so this says "voice"; whether the call is video comes from the notification. */
    private static int fromForegroundService(Context ctx, String pkg) {
        try {
            final ActivityManager am = ctx.getSystemService(ActivityManager.class);
            final PackageManager pm = ctx.getPackageManager();
            for (ActivityManager.RunningServiceInfo si : am.getRunningServices(Integer.MAX_VALUE)) {
                if (!si.foreground || si.service == null || !pkg.equals(si.service.getPackageName())) continue;
                final ServiceInfo info;
                try { info = pm.getServiceInfo(si.service, 0); } catch (Throwable t) { continue; }
                if ((info.getForegroundServiceType() & ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL) != 0) {
                    return VOICE;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getRunningServices", t);
        }
        return NONE;
    }

    private static int fromRecording(Context ctx, String pkg) {
        try {
            final int uid = ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
            final AudioManager am = ctx.getSystemService(AudioManager.class);
            for (AudioRecordingConfiguration c : am.getActiveRecordingConfigurations()) {
                if (c.getClientAudioSource() != MediaRecorder.AudioSource.VOICE_COMMUNICATION) continue;
                int cuid = -1;
                try { cuid = c.getClientUid(); } catch (Throwable ignore) { }
                // Anonymised (should not happen for the system uid): accept only while the
                // whole device is in communication mode, i.e. some VoIP call is really up.
                if (cuid == uid || (cuid == -1 && am.getMode() == AudioManager.MODE_IN_COMMUNICATION)) {
                    return VOICE;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "recording configs", t);
        }
        return NONE;
    }
}
