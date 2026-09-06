package com.nubia.rmcontrol;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

/**
 * "Monitor only": turn the phone's own panel off while the external display keeps running
 * (video on a TV / XR glasses without the phone glowing next to it).
 *
 * <p>Verified live 2026-09-06 (memory nx809j_phone_touchpad_mode): the external display sits in
 * its own display group on this ROM, and the hidden
 * {@code IPowerManager.goToSleepWithDisplayId(DEFAULT_DISPLAY, ...)} sleeps only the default
 * group → display 0 goes OFF, global wakefulness stays Awake, the keyguard is not armed, the
 * monitor keeps showing content. The power button (PhoneWindowManager checks the default
 * display's own state) or DT2W wakes the phone again, unlocked. The plain
 * {@code goToSleep} binder call that the power button uses is no good here: it also sleeps every
 * "default-adjacent" group, which an external display is (FLAG_DEFAULT_GROUP_ADJACENT).
 * Needs DEVICE_POWER (signature; this app runs as android.uid.system).
 *
 * <p>The service holds a display-scoped wake lock on the external display so a browser or a
 * gallery does not let the monitor time out either, and stops itself once the phone panel is
 * back on or the monitor is unplugged (in which case it wakes the phone, so the user is not left
 * with everything dark).
 */
public class MonitorOnlyService extends Service {
    private static final String TAG = "RmMonitorOnly";

    private PowerManager.WakeLock mExtWake;
    private int mExtDisplayId = Display.INVALID_DISPLAY;
    private DisplayManager mDm;
    /** Set once display 0 has actually been put to sleep; before that, display-0 change events
     *  (the ACQUIRE_CAUSES_WAKEUP of our external wake lock fires one) must not end the mode. */
    private boolean mSlept;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private final DisplayManager.DisplayListener mListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY && mSlept && phoneScreenOn(MonitorOnlyService.this)) {
                Log.i(TAG, "phone screen back on, done");
                stopSelf();
            }
        }
        @Override public void onDisplayRemoved(int id) {
            if (id == mExtDisplayId) {
                Log.i(TAG, "external display removed, waking the phone");
                wakePhone();
                stopSelf();
            }
        }
    };

    /** True when the default display is interactive (not OFF / DOZE / DOZE_SUSPEND). */
    static boolean phoneScreenOn(Context ctx) {
        final DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        final Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return true;
        final int s = d.getState();
        return s != Display.STATE_OFF && s != Display.STATE_DOZE && s != Display.STATE_DOZE_SUSPEND;
    }

    /** Sleep the phone's display group only; the external display's group is untouched. */
    static boolean sleepPhone() {
        try {
            final IPowerManager pm = IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE));
            pm.goToSleepWithDisplayId(Display.DEFAULT_DISPLAY, SystemClock.uptimeMillis(),
                    PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "goToSleepWithDisplayId", t);
            return false;
        }
    }

    static void wakePhone() {
        try {
            final IPowerManager pm = IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE));
            pm.wakeUpWithDisplayId(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_APPLICATION,
                    "rmcontrol:monitoronly", "com.nubia.rmcontrol", Display.DEFAULT_DISPLAY);
        } catch (Throwable t) {
            Log.e(TAG, "wakeUpWithDisplayId", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mExtDisplayId = TouchpadActivity.externalDisplayId(this);
        if (mExtDisplayId == Display.INVALID_DISPLAY) {
            Toast.makeText(this, "Connect an external screen first", Toast.LENGTH_SHORT).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        mDm = getSystemService(DisplayManager.class);
        if (mDm != null) mDm.registerDisplayListener(mListener, mMain);
        if (mExtWake == null) {
            try {
                final PowerManager pm = getSystemService(PowerManager.class);
                mExtWake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP, "rmcontrol:monitoronly", mExtDisplayId);
                mExtWake.acquire();
            } catch (Throwable t) {
                Log.w(TAG, "external wake lock", t);
            }
        }
        // Let the quick-settings panel close first; otherwise the phone wakes up into an open
        // shade later. The broadcast needs BROADCAST_CLOSE_SYSTEM_DIALOGS (signature, granted).
        try { sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)); } catch (Throwable ignored) {}
        mSlept = false;
        mMain.postDelayed(() -> {
            if (sleepPhone()) {
                mSlept = true;
                Log.i(TAG, "phone display asleep, external display " + mExtDisplayId + " stays on");
            } else {
                Toast.makeText(this, "Could not turn the phone screen off", Toast.LENGTH_SHORT).show();
                stopSelf();
            }
        }, 400);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mDm != null) mDm.unregisterDisplayListener(mListener);
        try { if (mExtWake != null && mExtWake.isHeld()) mExtWake.release(); } catch (Throwable ignored) {}
        mExtWake = null;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
