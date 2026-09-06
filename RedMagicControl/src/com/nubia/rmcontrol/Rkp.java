package com.nubia.rmcontrol;

import android.content.Intent;
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
 * THE NON-OBVIOUS PART. rkpdapp does NOT read remote_provisioning.hostname when it serves a
 * request: it reads a SharedPreferences entry "url" (Settings.getUrl), and that entry is written
 * only by Settings.resetDefaultConfig -- from BootReceiver on BOOT_COMPLETED, and from a few
 * server-error paths that a request never reaches while the URL is empty
 * (RemoteProvisioningService.java:63 refuses first). So a device that booted with the hostname
 * unset has "url" stored EMPTY, and setting the hostname afterwards changes nothing: every request
 * keeps failing with "RKP is disabled. System configured with no default URL." until the next
 * boot. Restarting rkpdapp does not help either (the pref is on disk). That is exactly why an
 * earlier investigation concluded the TEE was broken and RKP unreachable.
 *
 * The fix is to make rkpdapp run resetDefaultConfig again once the hostname is in place: this
 * app is android.uid.system, so it may send the protected BOOT_COMPLETED broadcast, and
 * targeting it at rkpdapp alone re-runs its BootReceiver (reset url + re-enqueue the daily job)
 * without waking anything else. Verified on hardware 2026-09-04: the stored url went from ""
 * to https://remoteprovisioning.grapheneos.org/v1 and the request went through.
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

    // Nothing to do at boot: init re-fires rkp.rc from the persisted property long before
    // BOOT_COMPLETED, so rkpdapp's own BootReceiver already stores the right url.

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

        // init handles the property trigger asynchronously; wait for the hostname to reflect the
        // choice before telling rkpdapp to re-read it, else it stores the old value again.
        resyncWhenSettled(ctx, on, 0);
        Log.i(TAG, "RKP " + (on ? "enabled" : "disabled"));
    }

    private static final String PROP_HOSTNAME = "remote_provisioning.hostname";
    private static final int RESYNC_POLL_MS = 100;
    private static final int RESYNC_MAX_POLLS = 30;

    private static void resyncWhenSettled(Context ctx, boolean on, int polls) {
        final boolean settled = Prop.get(PROP_HOSTNAME, "").isEmpty() != on;
        if (settled || polls >= RESYNC_MAX_POLLS) {
            if (!settled) Log.w(TAG, "RKP: hostname not visible after " + polls + " polls; resyncing anyway");
            resyncRkpd(ctx);
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> resyncWhenSettled(ctx, on, polls + 1), RESYNC_POLL_MS);
    }

    /**
     * Make rkpdapp store the current hostname as its url (Settings.resetDefaultConfig) by
     * re-delivering BOOT_COMPLETED to it alone. BOOT_COMPLETED is a protected broadcast, which
     * only the system uid may send -- and this app is the system uid. The receiver is
     * exported=false; an explicit package target from the same uid still reaches it.
     */
    private static void resyncRkpd(Context ctx) {
        try {
            final Intent i = new Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(RKPD_PKG);
            ctx.sendBroadcast(i);
            Log.i(TAG, "RKP: re-delivered BOOT_COMPLETED to " + RKPD_PKG);
        } catch (Throwable t) {
            Log.w(TAG, "RKP: could not resync " + RKPD_PKG + "; a reboot will apply it", t);
        }
    }

    private Rkp() {}
}
