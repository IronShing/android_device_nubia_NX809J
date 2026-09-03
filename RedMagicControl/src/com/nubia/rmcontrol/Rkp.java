package com.nubia.rmcontrol;

import android.app.ActivityManager;
import android.content.Context;
import android.provider.DeviceConfig;
import android.util.Log;

/**
 * Remote Key Provisioning (RKP) toggle.
 *
 * WHY THIS EXISTS. This device ships with NO attestation keys: KeyMint answers
 * DeviceGenerateKey with -74 (ATTESTATION_KEYS_NOT_PROVISIONED), /mnt/vendor/persist/keybox is
 * empty, and the Qualcomm QWES attestation-cert licence fails with error 16. Installing a local
 * keybox is a dead end -- there is no keybox to install, and KmInstallKeybox reprovisioning is an
 * RMA operation needing a devcfg signed with the device serial.
 *
 * RKP is the route that works. nubia/ZTE DID enrol this unit's RKP keys in Google's backend:
 * with RKP pointed at a provisioning server the device fetches signed keys and gets a full
 * Google-rooted chain (TEE key -> Droid CA3 -> Droid CA2 -> Key Attestation CA1). Verified
 * end-to-end on hardware 2026-09-01.
 *
 * THE NON-OBVIOUS PART. rkpdapp reads remote_provisioning.hostname ONCE at process start. Setting the
 * property while it is running changes nothing and it keeps reporting "no default URL" -- which
 * is exactly why an earlier investigation concluded the TEE was broken and RKP unreachable. The
 * app must be restarted after the property changes; {@link #kickRkpd} does that.
 *
 * WHY NOT remote_provisioning.enable_rkpd: it has no exact SELinux context and falls back to
 * default_prop, which nothing here should be allowed to write wholesale. Tested on hardware
 * 2026-09-01: RKP provisions a full Google-rooted chain with that property left at false, because
 * the real gate is the device_config flag below. It is deliberately not set.
 *
 * Default OFF, and it should stay that way: with RKP enabled, rkpdapp schedules a daily
 * PeriodicProvisioner job that wakes the device and hits the network.
 */
final class Rkp {

    private static final String TAG = "RMControl";

    /** The user's choice. rm_ctrl_prop, so the app may set it; persists across reboots. */
    static final String PROP_ENABLED = "persist.sys.rm.rkp";

    // The remote_provisioning.* properties are set by init from rkp.rc, not from here. They are
    // NOT persist.* properties, so they vanish on reboot -- but persist.sys.rm.rkp does persist and
    // init re-fires the trigger at boot, so the user's choice survives without RmApp doing anything.


    private static final String RKPD_PKG = "com.android.rkpdapp";
    private static final String DC_NAMESPACE = "remote_key_provisioning_native";
    private static final String DC_KEY = "enable_rkpd";

    static boolean isEnabled() {
        return Prop.getBool(PROP_ENABLED, false);
    }

    /**
     * Re-assert the user's choice at boot. init already re-fires the rkp.rc trigger from the
     * persisted property, so this only needs to run when RKP is ON -- and then only to make sure
     * rkpdapp starts life with the hostname already set. Doing nothing when off avoids killing
     * rkpdapp on every boot for no reason.
     */
    static void reapply(Context ctx) {
        if (!isEnabled()) return;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> kickRkpd(ctx), 4000);
    }

    static void apply(Context ctx, boolean on) {
        // Writing our own property is all the app may do: init picks it up from rkp.rc and does
        // the remote_provisioning.* setprops. system_app is neverallowed from touching
        // remote_prov_prop directly (property.te), and that is a build-time check.
        Prop.set(PROP_ENABLED, on ? "1" : "0");

        try {
            DeviceConfig.setProperty(DC_NAMESPACE, DC_KEY, on ? "true" : "false", false);
        } catch (Throwable t) {
            Log.e(TAG, "RKP: device_config " + DC_KEY + " failed", t);
        }

        // init handles the property trigger asynchronously; give it a moment so rkpdapp restarts
        // after the hostname is actually in place rather than before.
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> kickRkpd(ctx), 400);
        Log.i(TAG, "RKP " + (on ? "enabled" : "disabled"));
    }

    /**
     * Restart rkpdapp so it re-reads the hostname. Uses killBackgroundProcesses (a NORMAL
     * permission) rather than forceStopPackage: FORCE_STOP_PACKAGES is signature|privileged and
     * would need a privapp-permissions entry, and a missing entry there boot-loops the device
     * under ro.control_privapp_permissions=enforce.
     */
    private static void kickRkpd(Context ctx) {
        try {
            final ActivityManager am = ctx.getSystemService(ActivityManager.class);
            if (am != null) am.killBackgroundProcesses(RKPD_PKG);
        } catch (Throwable t) {
            Log.w(TAG, "RKP: could not restart " + RKPD_PKG + "; a reboot will apply it", t);
        }
    }

    private Rkp() {}
}
