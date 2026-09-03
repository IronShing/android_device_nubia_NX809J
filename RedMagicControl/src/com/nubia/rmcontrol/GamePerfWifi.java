package com.nubia.rmcontrol;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.util.Log;

/**
 * "Max WiFi" while a game is running.
 *
 * GameSpace flips {@code persist.sys.power_mode_perf} to 1 when a listed game is in
 * the foreground and back to 0 on exit. gameperfd already pins CPU/GPU/fan/pump and
 * steers the WLAN IRQs to a gold core. The one latency lever it could not add is
 * WiFi power-save: the radio otherwise sleeps between packets, which is what spikes
 * ping in games. This holds a WiFi lock for the duration of game mode to keep the
 * link awake, and releases it the instant the game exits.
 *
 * Why HIGH_PERF and not LOW_LATENCY: LOW_LATENCY only activates while the *acquiring*
 * app is itself foreground (WifiLockManager#canAppActivateLowLatencyLock -> mIsFg),
 * and this is a background system app, so a LOW_LATENCY lock taken here would never
 * engage. HIGH_PERF only requires WiFi to be connected
 * (WifiLockManager#canActivateHighPerfLock -> mStaConnected), so it works from the
 * background and still disables power-save -- the actual win. The lock lives here
 * rather than in the native gameperfd because a WifiLock needs a framework context
 * the daemon does not have; RmApp is persistent, so this holder never gets killed.
 */
final class GamePerfWifi {

    private static final String TAG = "RMControl";
    private static final String PROP = "persist.sys.power_mode_perf";

    /** How often we re-read the property. A property read is a shared-memory read, not IPC. */
    private static final long POLL_MS = 2000L;

    private GamePerfWifi() {}

    static void start(Context ctx) {
        final WifiManager wm = ctx.getSystemService(WifiManager.class);
        if (wm == null) {
            Log.e(TAG, "GamePerfWifi: no WifiManager; max-wifi disabled");
            return;
        }

        final WifiManager.WifiLock lock =
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "rmcontrol:gameperf");
        lock.setReferenceCounted(false);

        final Runnable sync = new Runnable() {
            @Override
            public void run() {
                try {
                    boolean gameMode = "1".equals(SystemProperties.get(PROP, "0"));
                    if (gameMode && !lock.isHeld()) {
                        lock.acquire();
                        Log.i(TAG, "GamePerfWifi: HIGH_PERF wifi lock acquired (game mode on)");
                    } else if (!gameMode && lock.isHeld()) {
                        lock.release();
                        Log.i(TAG, "GamePerfWifi: HIGH_PERF wifi lock released (game mode off)");
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "GamePerfWifi: sync failed", t);
                }
            }
        };

        // Best-effort fast path only. addChangeCallback() has NEVER delivered a callback to
        // this process on this device -- the same thing SliderWatcher documents (verified
        // 2026-08-20, ten physical slides, zero callbacks). Relying on it alone is why max-wifi
        // silently never engaged: verified 2026-08-29 on the 20260829 build, flipping
        // persist.sys.power_mode_perf 0->1 produced no callback, no log and no WifiLock.
        try {
            SystemProperties.addChangeCallback(sync);
        } catch (Throwable t) {
            Log.e(TAG, "GamePerfWifi: addChangeCallback unavailable; polling only", t);
        }

        // The poll is what actually makes the feature work. A property read is a
        // shared-memory read, not IPC, and game mode changes at most a few times an hour,
        // so 2s costs nothing and bounds the boost's lag behind GameSpace.
        final HandlerThread th = new HandlerThread("gameperf-wifi");
        th.start();
        final Handler h = new Handler(th.getLooper());
        h.post(new Runnable() {
            private boolean mFirst = true;
            @Override public void run() {
                if (mFirst) {
                    mFirst = false;
                    // Logged from the poll thread, not from Application.onCreate: a persistent
                    // app starts before logd is accepting, so anything logged there is lost.
                    Log.i(TAG, "GamePerfWifi: polling (" + PROP + "="
                            + SystemProperties.get(PROP, "0") + ")");
                }
                sync.run();
                h.postDelayed(this, POLL_MS);
            }
        });
    }
}
