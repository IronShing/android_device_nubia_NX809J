package com.nubia.rmcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

/**
 * Run the fan and the liquid pump while the phone is fast charging.
 *
 * Fast charging is where this device makes most of its heat while the user is not
 * holding it, and the stock cooling only reacts to game load. This turns charging
 * itself into a trigger.
 *
 * Detection is by CURRENT, not by the charger's charge_type. charge_type reports
 * "Fast" for the whole CC phase, including down at 0.4 A near the charge limit, so
 * gating on it would spin the fan for hours at trickle current. Current draw is also
 * the honest proxy for heat, which is the thing we are actually reacting to.
 * Hysteresis stops ordinary current ripple near the threshold flapping the fan.
 *
 * Everything is read through BatteryManager rather than /sys/class/power_supply:
 * the app runs as system_app, which has no guaranteed read access to battery sysfs,
 * and the framework API needs no sepolicy at all.
 *
 * The user's own fan/pump levels are saved before we override and restored when
 * charging stops, so this never silently eats a manual setting.
 */
final class ChargeCooling {

    private static final String TAG = "RMControl";

    // user-facing toggle + tunables
    static final String PROP_ENABLED   = "persist.sys.rm.chargecool";        // "1" = on
    static final String PROP_FAN_LEVEL = "persist.sys.rm.chargecool.fan";    // 0..5 or FAN_AUTO
    /** Sentinel level meaning "let hwcontrol's temperature curve drive the fan". */
    static final String FAN_AUTO       = "auto";
    static final String PROP_ON_UA     = "persist.sys.rm.chargecool.on_ua";  // default 2000000
    // Our own request prop. vendor_init acts on THIS and writes the hardware itself.
    //
    // We deliberately do NOT write persist.sys.fan.level any more. Doing so used to fire
    // the manual-level trigger, which overwrote whatever game cooling had just written --
    // observed live as a stopped fan while power_mode_perf=1 and gamecool=1 were both set.
    // Restoring the user's level is now vendor_init's job (it reads persist.sys.fan.level
    // itself), so there is no saved-state prop to drift out of sync.
    private static final String PROP_ACTIVE = "persist.sys.rm.chargecool.active";

    private static final int DEFAULT_ON_UA = 2000000;   // 2 A
    private static final int OFF_MARGIN_UA = 400000;    // hysteresis: off 0.4 A below on

    private static boolean sHolding = false;
    /** Which active value we last published ("1" fixed / "2" auto), so a mode change re-fires. */
    private static String sHeldMode = "";
    private static BatteryManager sBm;

    private ChargeCooling() { }

    static void start(Context ctx) {
        sBm = ctx.getSystemService(BatteryManager.class);
        final BroadcastReceiver rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                final int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                evaluate(status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL);
            }
        };
        // ACTION_BATTERY_CHANGED is sticky, so registering also delivers current state --
        // that matters because the phone can boot already plugged in and charging.
        ctx.registerReceiver(rx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    /**
     * Re-check immediately, without waiting for the next ACTION_BATTERY_CHANGED.
     *
     * Needed because the settings toggle must take effect now: battery broadcasts can be
     * ~90 s apart when nothing is changing (measured 2026-08-26), so a user turning this
     * off would otherwise watch the fan keep running. SystemProperties.addChangeCallback
     * is NOT usable for this -- on this device it never delivers (see SliderWatcher) --
     * but the settings UI is in this same process, so a direct call is free and reliable.
     */
    static void reevaluate(Context ctx) {
        boolean charging = false;
        try {
            // null receiver + sticky ACTION_BATTERY_CHANGED returns current state without registering
            final Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                final int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = (st == BatteryManager.BATTERY_STATUS_CHARGING
                        || st == BatteryManager.BATTERY_STATUS_FULL);
            }
        } catch (Throwable t) {
            Log.e(TAG, "reevaluate: battery state unavailable", t);
        }
        evaluate(charging);
    }

    static void evaluate(boolean charging) {
        if (!"1".equals(Prop.get(PROP_ENABLED, "1").trim())) {
            release();                       // toggled off mid-charge
            return;
        }
        if (charging && isFastCharging()) hold(); else release();
    }

    private static boolean isFastCharging() {
        if (sBm == null) return false;
        // CURRENT_NOW is microamps here, and is signed: negative while discharging.
        final int ua = Math.abs(sBm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
        if (ua == 0) return false;
        final int on = parseInt(Prop.get(PROP_ON_UA, ""), DEFAULT_ON_UA);
        // hysteresis: once holding, stay on until we drop clearly below the threshold
        return sHolding ? ua > (on - OFF_MARGIN_UA) : ua >= on;
    }

    private static void hold() {
        // 1 = pump + a fixed fan speed; 2 = pump only, fan left to the auto curve. Two values
        // rather than one because init cannot branch on a property that is not a number, and
        // writing "auto" into fan_speed_level would just put garbage in the node.
        final boolean auto = FAN_AUTO.equals(Prop.get(PROP_FAN_LEVEL, FAN_AUTO).trim());
        final String want = auto ? "2" : "1";
        if (sHolding && want.equals(sHeldMode)) return;
        Prop.set(PROP_ACTIVE, want);
        sHolding = true;
        sHeldMode = want;
        Log.i(TAG, "chargecool: fast charge -> cooling requested (" 
                + (auto ? "pump + auto fan" : "pump + fixed fan") + ")");
    }

    private static void release() {
        if (!sHolding) return;
        Prop.set(PROP_ACTIVE, "0");
        sHolding = false;
        sHeldMode = "";
        Log.i(TAG, "chargecool: charge ended -> released");
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

}
