package com.nubia.rmcontrol;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.Log;

/**
 * Desktop-mode plumbing for the bundled MagicDesk.
 *
 * Two jobs, both of which exist to remove friction the ROM created or that MagicDesk would
 * otherwise have to ask Shizuku for:
 *
 *   1. Grant MagicDesk the display-over-apps app-op, so its "Prepare device" step has one less
 *      thing to do (and one less reason to need Shizuku before it can show anything).
 *
 *   2. Expose on-device desktop hosting as a switch. This ROM ships
 *      config_canInternalDisplayHostDesktops=false because that bool is what draws the Android 16
 *      white "app handle" pill on top of every fullscreen app. The same bool also gates desktop
 *      windowing on the internal display, and the two cannot be separated — enable_drawing_app_handle
 *      only selects how the handle is rendered, and canInternalDisplayHostDesktops short-circuits
 *      canEnterDesktopMode() outright. So it is a genuine either/or, and the honest thing is to let
 *      the user decide rather than to pick for them and call it a bug.
 *
 *      External and projected displays take a different path (isEligibleForDesktopMode), so
 *      MagicDesk on an HDMI/DisplayPort monitor works regardless of this switch.
 */
final class DesktopIntegration {

    private static final String TAG = "RMControl";

    static final String MAGICDESK_PKG = "io.github.mekhontsev.magicdesk";
    static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    /** Mutable /product/overlay RRO that flips config_canInternalDisplayHostDesktops back to true. */
    static final String DESKTOP_OVERLAY = "com.nx809j.overlay.showapphandle";

    private DesktopIntegration() {}

    static boolean isInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Give MagicDesk SYSTEM_ALERT_WINDOW, but only if nobody has expressed an opinion yet.
     *
     * Preinstalling is not sufficient by itself: this platform declares SYSTEM_ALERT_WINDOW as
     * {@code signature|setup|appop|installer|pre23|development} — note the absence of
     * {@code preinstalled} — so being on the system image grants nothing.
     *
     * We only ever promote MODE_DEFAULT (never explicitly set) to MODE_ALLOWED. If the user revokes
     * the permission the op becomes MODE_IGNORED or MODE_ERRORED, and we must leave it that way
     * instead of quietly re-granting on every boot.
     */
    static void grantOverlayOpIfUntouched(Context ctx) {
        if (!isInstalled(ctx, MAGICDESK_PKG)) return;
        try {
            final int uid = ctx.getPackageManager().getPackageUid(MAGICDESK_PKG, 0);
            final AppOpsManager aom = ctx.getSystemService(AppOpsManager.class);
            if (aom == null) return;

            final int mode = aom.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, MAGICDESK_PKG);
            if (mode != AppOpsManager.MODE_DEFAULT) {
                return;   // already allowed, or deliberately denied — either way, not ours to change
            }
            aom.setMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, MAGICDESK_PKG,
                    AppOpsManager.MODE_ALLOWED);
            Log.i(TAG, "granted SYSTEM_ALERT_WINDOW to " + MAGICDESK_PKG);
        } catch (Throwable t) {
            // Non-fatal: MagicDesk's own Prepare device can still do this through Shizuku.
            Log.w(TAG, "could not pre-grant overlay op to MagicDesk", t);
        }
    }

    /** True when the internal display may host desktops (and therefore shows the app handle). */
    static boolean isOnDeviceDesktopEnabled(Context ctx) {
        try {
            final OverlayManager om = ctx.getSystemService(OverlayManager.class);
            if (om == null) return false;
            final OverlayInfo info = om.getOverlayInfo(DESKTOP_OVERLAY, UserHandle.SYSTEM);
            return info != null && info.isEnabled();
        } catch (Throwable t) {
            Log.w(TAG, "could not read desktop overlay state", t);
            return false;
        }
    }

    /**
     * Enable/disable on-device desktop hosting. Equivalent to
     * {@code cmd overlay enable|disable com.nx809j.overlay.showapphandle}, which is what people are
     * told to run by hand today.
     *
     * @return true if the request was accepted
     */
    static boolean setOnDeviceDesktopEnabled(Context ctx, boolean enable) {
        try {
            final OverlayManager om = ctx.getSystemService(OverlayManager.class);
            if (om == null) return false;
            om.setEnabled(DESKTOP_OVERLAY, enable, UserHandle.SYSTEM);
            Log.i(TAG, "on-device desktop " + (enable ? "enabled" : "disabled"));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "could not toggle desktop overlay", t);
            return false;
        }
    }
}
