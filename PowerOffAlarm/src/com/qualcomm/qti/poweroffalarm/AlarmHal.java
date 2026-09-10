package com.qualcomm.qti.poweroffalarm;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import vendor.qti.hardware.alarm.IAlarm;

/** Thin wrapper over the vendor RTC-alarm HAL (vendor.qti.hardware.alarm.IAlarm/default). */
final class AlarmHal {
    private static final String TAG = "PowerOffAlarm";
    private static final String NAME = "vendor.qti.hardware.alarm.IAlarm/default";

    private AlarmHal() {}

    private static IAlarm get() {
        if (!ServiceManager.isDeclared(NAME)) {
            Log.w(TAG, NAME + " not declared in the vendor manifest");
            return null;
        }
        // The service is "oneshot disabled" in its rc; waitForService makes
        // servicemanager start it on demand (interface aidl ... declaration).
        IBinder b = ServiceManager.waitForService(NAME);
        IAlarm hal = IAlarm.Stub.asInterface(b);
        if (hal == null) Log.e(TAG, NAME + " declared but not found");
        return hal;
    }

    /** RTC counter in seconds, or -1. */
    static long rtcNow() {
        try {
            IAlarm h = get();
            return h == null ? -1 : h.getRtcTime();
        } catch (RemoteException e) {
            Log.e(TAG, "getRtcTime", e);
            return -1;
        }
    }

    /** Program an absolute RTC second. */
    static boolean set(long rtcSeconds) {
        try {
            IAlarm h = get();
            return h != null && h.setAlarm(rtcSeconds) == 0;
        } catch (RemoteException e) {
            Log.e(TAG, "setAlarm", e);
            return false;
        }
    }

    static boolean cancel() {
        try {
            IAlarm h = get();
            return h != null && h.cancelAlarm() == 0;
        } catch (RemoteException e) {
            Log.e(TAG, "cancelAlarm", e);
            return false;
        }
    }
}
