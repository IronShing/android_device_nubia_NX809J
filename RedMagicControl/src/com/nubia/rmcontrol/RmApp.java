package com.nubia.rmcontrol;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.Network;
import android.util.Log;

/**
 * Application hook for work that must run without the user opening the panel.
 *
 * The app is android:persistent="true", so AMS creates this at boot and keeps the
 * process alive — which is what makes the offline-Doze revert path trustworthy: the
 * callback that restores stock timings cannot be killed off in the background.
 */
public class RmApp extends Application {

    private static final String TAG = "RMControl";

    private final ConnectivityManager.NetworkCallback mNetCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    // A network exists again -> notifications can be delivered -> stock timings.
                    OfflineDoze.apply(RmApp.this, false);
                }

                @Override
                public void onLost(Network network) {
                    OfflineDoze.apply(RmApp.this, true);
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();

        // The device can boot straight into airplane mode, so evaluate now rather than
        // waiting for a transition.
        OfflineDoze.reapply(this);

        // Magic Slider: slider_uewake publishes the switch state; we turn it into an action.
        // Nothing happens unless the user picks a mode (persist.sys.rm.slider.mode).
        SliderWatcher.start(this);

        // Fast charging is the other time this device gets hot while nobody is holding it.
        // No-op unless the user enables it (persist.sys.rm.chargecool).
        ChargeCooling.start(this);

        // "Max WiFi" during games: keep the link out of power-save while GameSpace has
        // flipped persist.sys.power_mode_perf. No-op until a game triggers it.
        GamePerfWifi.start(this);

        // RKP needs nothing at boot: init re-fires rkp.rc from persist.sys.rm.rkp before
        // BOOT_COMPLETED, and rkpdapp's own BootReceiver picks the hostname up from there.

        // External-display caps ("no more than 1080p/60") re-applied on every connection.
        // No-op until the user sets a limit; must live here rather than in the panel because the
        // whole point is that it applies without anyone opening the app.
        ExternalDisplay.start(this);

        // Ship "Fast" as the animation-speed default. One-shot; the user's own choice wins
        // from then on.
        AnimationDefaults.applyOnce(this);

        // Under-display selfie camera: black out the pixels above the sensor while a third-party
        // app streams from it (stock SystemUI does this; AOSP SystemUI does not). See UdcMask.
        UdcMask.start(this);

        // Game super-resolution / frame interpolation (Qualcomm GPP on the NPU). The live
        // vendor.gpp.* properties do not persist, so re-issue the user's choice every boot.
        GamePostProcessing.apply();

        // Bundled MagicDesk needs display-over-apps. Doing it here saves it asking Shizuku for
        // something we can grant directly; it is a no-op once the op has any explicit value.
        DesktopIntegration.grantOverlayOpIfUntouched(this);

        final ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) {
            Log.e(TAG, "no ConnectivityManager; offline-doze will not track network changes");
            return;
        }
        try {
            cm.registerDefaultNetworkCallback(mNetCallback);
        } catch (Throwable t) {
            // Leave whatever state reapply() just set rather than risking a stuck override.
            Log.e(TAG, "registerDefaultNetworkCallback failed", t);
        }
    }
}
