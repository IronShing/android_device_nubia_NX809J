package com.nubia.rmcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * Charge separation ("bypass charging"): run the phone off the adapter with the battery
 * charger OFF, so a plugged-in gaming session does not cook the battery.
 *
 * Hardware: /sys/class/qcom-battery/battery_charging_enabled=0 stops only the battery
 * charger -- the USB input path stays up, battery current goes 4 A -> 0 A within ~15 s,
 * the level holds and the status stays "Charging" (verified 2026-09-20). It is the node the
 * stock ChargeSeparation app flips. NOT charging_enabled: that suspends the input path and
 * the phone drains while plugged.
 *
 * This class only publishes the EFFECTIVE state as persist.sys.rm.chargesep.active;
 * vendor_init (redmagic_hw_arm.rc) does the node write, the same shape as chargecool. The
 * Lineage health HAL shares the node and reads the prop so Settings > Battery > Charging
 * control can never re-enable the charger under us.
 *
 * Effective = (manual tile on OR ("during game mode" AND persist.sys.power_mode_perf=1))
 *             AND battery above the minimum level
 *             AND not suspended by the screen-off timer.
 * The manual switch is a session thing (stock parity): it clears at boot and, by default,
 * a few minutes after the screen goes off so the phone still charges overnight.
 */
final class ChargeSeparation {

    private static final String TAG = "RMControl";

    /** Manual switch (QS tile). Cleared at boot. */
    static final String PROP_MANUAL     = "persist.sys.rm.chargesep";
    /** "1" = separate automatically while a Performance-mode game is in the foreground. */
    static final String PROP_GAME       = "persist.sys.rm.chargesep.game";
    /** Minutes of screen-off after which separation is dropped; 0 = never. */
    static final String PROP_SCREENOFF_MIN = "persist.sys.rm.chargesep.screenoff_min";
    /** Battery % at or below which separation is suspended so the phone still charges. */
    static final String PROP_MIN_LEVEL  = "persist.sys.rm.chargesep.min";
    /** Effective state, consumed by redmagic_hw_arm.rc and the health HAL. */
    static final String PROP_ACTIVE     = "persist.sys.rm.chargesep.active";

    private static final String PROP_PERF = "persist.sys.power_mode_perf";
    /**
     * Bumped whenever a charger is (re)attached while separation is active: init re-writes the
     * node (redmagic_hw_arm.rc). Init only acts on a CHANGE of .active, and the charger driver
     * re-enables the battery charger on every attach, so without this a replug silently
     * charged behind a tile that still read On (XDA #227/#230).
     */
    private static final String PROP_REAPPLY = "sys.rm.chargesep.reapply";
    /**
     * Bumped on attach while active, BEFORE the re-apply: init writes the node back to 1 so the
     * charger firmware runs its fast-charge handshake. The ADSP charger only negotiates the
     * 9/18 V contract while it is allowed to charge -- with the battery already cut at attach
     * it sits on the 5 V default (icl 2.25 A = 11 W) for the whole session, and the game then
     * pulls the rest from the battery (XDA #241, Bobo9996 65 % logs: separation OFF ->
     * V=9000 mV within 0.8 s; separation back ON -> the 9 V contract held). So: attach ->
     * allow -> grace -> re-apply, and the session runs off the negotiated contract.
     */
    private static final String PROP_ALLOW = "sys.rm.chargesep.allow";
    /** Milliseconds of charging allowed after attach before the bypass is re-applied. */
    private static final String PROP_GRACE_MS = "persist.sys.rm.chargesep.grace_ms";
    static final int DEFAULT_GRACE_MS = 8000;

    static final int DEFAULT_SCREENOFF_MIN = 5;
    static final int DEFAULT_MIN_LEVEL = 20;
    private static final long POLL_MS = 2000L;

    private static Handler sH;
    private static boolean sScreenOff = false;
    private static boolean sScreenOffExpired = false;
    private static int sLevel = 100;
    private static int sPlugged = -1;   // BatteryManager.EXTRA_PLUGGED, -1 = not seen yet
    private static boolean sActive = false;
    private static final Runnable sReapply = () -> {
        if (sActive && sPlugged > 0) {
            Prop.set(PROP_REAPPLY, Long.toString(System.currentTimeMillis()));
            Log.i(TAG, "chargesep: grace over -> re-apply");
        }
    };
    private static final Runnable sScreenOffTimeout = () -> {
        // Stock parity: the switch is for a session in hand, not for the night. Drop the
        // manual switch too, so the tile reads Off when the user picks the phone up.
        sScreenOffExpired = true;
        if (Prop.getBool(PROP_MANUAL, false)) {
            Prop.set(PROP_MANUAL, "0");
            Log.i(TAG, "chargesep: screen off for " + screenOffMinutes() + " min -> manual off");
        }
        evaluate();
    };

    private ChargeSeparation() { }

    static void start(Context ctx) {
        // The manual switch never survives a reboot; the persisted prop would otherwise hold
        // the charger off from post-fs-data on a phone that may sit on the charger all day.
        if (Prop.getBool(PROP_MANUAL, false)) {
            Prop.set(PROP_MANUAL, "0");
            Log.i(TAG, "chargesep: boot -> manual switch cleared");
        }

        final HandlerThread th = new HandlerThread("chargesep");
        th.start();
        sH = new Handler(th.getLooper());

        final BroadcastReceiver rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                final String a = i.getAction();
                if (Intent.ACTION_BATTERY_CHANGED.equals(a)) {
                    final int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    final int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    if (level >= 0 && scale > 0) sLevel = level * 100 / scale;
                    final int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                    if (sPlugged != plugged) {
                        final int was = sPlugged;
                        sPlugged = plugged;
                        if (was > 0 && plugged == 0) {
                            sH.removeCallbacks(sReapply);
                            if (Prop.getBool(PROP_MANUAL, false)) {
                                // Unplugging ends the session: the next plug-in charges
                                // normally until the user asks again, and the tile agrees
                                // with the cable.
                                Prop.set(PROP_MANUAL, "0");
                                Log.i(TAG, "chargesep: charger unplugged -> manual off");
                            }
                        } else if (was == 0 && plugged > 0 && sActive) {
                            // Let the charger negotiate its contract first (see PROP_ALLOW).
                            final int grace = graceMs();
                            Prop.set(PROP_ALLOW, Long.toString(System.currentTimeMillis()));
                            sH.removeCallbacks(sReapply);
                            sH.postDelayed(sReapply, grace);
                            Log.i(TAG, "chargesep: charger attached while active -> allow "
                                    + grace + " ms, then re-apply");
                        }
                    }
                } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    sScreenOff = true;
                    final int min = screenOffMinutes();
                    sH.removeCallbacks(sScreenOffTimeout);
                    if (min > 0) sH.postDelayed(sScreenOffTimeout, min * 60_000L);
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    sScreenOff = false;
                    sScreenOffExpired = false;
                    sH.removeCallbacks(sScreenOffTimeout);
                }
                sH.post(ChargeSeparation::evaluate);
            }
        };
        final IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);   // sticky
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        ctx.registerReceiver(rx, f);

        // Game mode is a property flip by GameSpace; addChangeCallback never fires in this
        // process (see GamePerfWifi), so poll. A property read is a shared-memory read.
        sH.post(new Runnable() {
            @Override public void run() {
                evaluate();
                sH.postDelayed(this, POLL_MS);
            }
        });
    }

    /** Settings/tile changed: apply now rather than at the next poll tick. */
    static void reevaluate() {
        if (sH != null) sH.post(ChargeSeparation::evaluate);
    }

    static boolean isActive() { return sActive; }

    static int graceMs() {
        return Math.max(0, Prop.getInt(PROP_GRACE_MS, DEFAULT_GRACE_MS));
    }

    static int screenOffMinutes() {
        return Prop.getInt(PROP_SCREENOFF_MIN, DEFAULT_SCREENOFF_MIN);
    }

    private static synchronized void evaluate() {
        final boolean manual = Prop.getBool(PROP_MANUAL, false);
        final boolean game = Prop.getBool(PROP_GAME, true)
                && "1".equals(Prop.get(PROP_PERF, "0").trim());
        final int min = Prop.getInt(PROP_MIN_LEVEL, DEFAULT_MIN_LEVEL);
        boolean want = (manual || game) && sLevel > min && !sScreenOffExpired;
        if (want == sActive && want == Prop.getBool(PROP_ACTIVE, false)) return;
        Prop.set(PROP_ACTIVE, want ? "1" : "0");
        sActive = want;
        Log.i(TAG, "chargesep: " + (want ? "ON" : "OFF") + " (manual=" + manual + " game=" + game
                + " level=" + sLevel + " min=" + min + " screenOff=" + sScreenOff
                + (sScreenOffExpired ? " expired" : "") + ")");
    }
}
