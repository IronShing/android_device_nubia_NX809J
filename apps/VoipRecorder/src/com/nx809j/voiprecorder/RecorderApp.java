package com.nx809j.voiprecorder;

import android.app.Application;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * The app's process is marked android:persistent in the manifest, so the system keeps it
 * resident (PROCESS_STATE_PERSISTENT) with NO notification, NO foreground service and NO
 * per-notification wake-ups. That solves the battery problem the old design had:
 *
 *   - the "visible" mode ran a foreground service typed MICROPHONE 24/7 (kept the process
 *     un-freezable and pinned as an active mic client), and
 *   - the "hide notification" mode kept a NotificationListenerService bound, which the
 *     system wakes on EVERY notification posted device-wide.
 *
 * Now nothing runs while idle. Detection is a single AppOps active-mic callback that fires
 * only when some app starts/stops using the microphone (see CallDetector); a persistent
 * process is also exempt from the background-FGS-start and background-mic restrictions
 * (ActiveServices: PROCESS_STATE_PERSISTENT), so the recording FGS can be started straight
 * from that callback. Only an actual call raises a microphone FGS + notification, and only
 * for its duration.
 *
 * The switches are in RedMagic Control; they arrive here as Settings.Secure changes.
 */
public class RecorderApp extends Application {
    private static final String TAG = "VoipRecorder";

    @Override public void onCreate() {
        super.onCreate();
        try {
            CallDetector.get(this).sync();   // arms the watcher iff the user enabled recording
            final ContentObserver obs = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean self, Uri uri) {
                    CallDetector.get(RecorderApp.this).sync();
                }
            };
            getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Prefs.SECURE_ENABLED), false, obs);
        } catch (Throwable t) {
            Log.e(TAG, "detector init failed", t);
        }
    }
}
