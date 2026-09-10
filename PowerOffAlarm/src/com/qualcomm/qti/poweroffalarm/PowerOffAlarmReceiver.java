package com.qualcomm.qti.poweroffalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Map;

/**
 * Bridges clock-app alarms to the PMIC RTC alarm so the phone powers itself on.
 *
 * Protocol (what DeskClock's AlarmStateManager sends, package-targeted):
 *   org.codeaurora.poweroffalarm.action.SET_ALARM    extra "time"  = wall-clock ms
 *   org.codeaurora.poweroffalarm.action.CANCEL_ALARM extra "time"  = wall-clock ms
 *   optional extra "alarm" = owner key (defaults to a single shared slot).
 *
 * The RTC can hold one alarm, so we keep an owner→time table in device-protected
 * prefs (works before the user unlocks) and always program the earliest entry
 * that is still comfortably in the future. The HAL wants an absolute RTC second;
 * the RTC counter is not wall-clock, so convert via (alarm - now) + rtcNow. The
 * alarm is set LEAD_MS early so the phone has booted by the time the clock app
 * needs to ring — DeskClock re-arms the real alarm on LOCKED_BOOT_COMPLETED.
 */
public class PowerOffAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "PowerOffAlarm";
    static final String ACTION_SET = "org.codeaurora.poweroffalarm.action.SET_ALARM";
    static final String ACTION_CANCEL = "org.codeaurora.poweroffalarm.action.CANCEL_ALARM";
    private static final String EXTRA_TIME = "time";
    private static final String EXTRA_OWNER = "alarm";
    private static final String DEFAULT_OWNER = "default_alarm_owner";
    private static final String PREFS = "alarm_list";
    /** Boot lead time; the stock client uses the same 90 s. */
    private static final long LEAD_MS = 90_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        long time = intent.getLongExtra(EXTRA_TIME, 0);
        String owner = intent.getStringExtra(EXTRA_OWNER);
        if (owner == null) owner = DEFAULT_OWNER;
        SharedPreferences table = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        if (ACTION_SET.equals(action)) {
            if (time <= 0) return;
            Log.d(TAG, "set owner=" + owner + " time=" + time);
            table.edit().putLong(owner, time).apply();
        } else if (ACTION_CANCEL.equals(action)) {
            Log.d(TAG, "cancel owner=" + owner + " time=" + time);
            // Only drop the entry if it is the one being cancelled (a newer SET for
            // the same owner may already have replaced it).
            if (table.getLong(owner, 0) == time || time == 0) table.edit().remove(owner).apply();
        } else {
            return;
        }
        reprogram(table);
    }

    /** Program the RTC with the earliest future alarm in the table, or clear it. */
    private static void reprogram(SharedPreferences table) {
        long now = System.currentTimeMillis();
        long next = 0;
        SharedPreferences.Editor gc = table.edit();
        for (Map.Entry<String, ?> e : table.getAll().entrySet()) {
            long t = e.getValue() instanceof Long ? (Long) e.getValue() : 0;
            if (t - now <= LEAD_MS) { gc.remove(e.getKey()); continue; }   // past / too close
            if (next == 0 || t < next) next = t;
        }
        gc.apply();

        if (next == 0) {
            Log.d(TAG, "no pending alarm, clearing RTC alarm: " + AlarmHal.cancel());
            return;
        }
        long rtc = AlarmHal.rtcNow();
        if (rtc < 0) { Log.e(TAG, "RTC unavailable, alarm not programmed"); return; }
        long target = rtc + (next - now - LEAD_MS) / 1000;
        boolean ok = AlarmHal.set(target);
        Log.d(TAG, "RTC alarm -> " + target + " (wall " + next + ", rtc now " + rtc + "): " + ok);
    }
}
