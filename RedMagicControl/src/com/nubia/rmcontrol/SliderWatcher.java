package com.nubia.rmcontrol;

import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Magic Slider handler.
 *
 * The physical slider is an input SWITCH (EV_SW / SW_PEN_INSERTED on gpio-keys_nubia). On stock
 * RedMagicOS it is consumed by ZTE's SlideKeysCtrl, which lives inside their system server; an
 * AOSP-based ROM has no such component, so the slider does nothing. slider_uewake (system_ext
 * coredomain daemon) reads the switch and publishes:
 *
 *   sys.rm.slider.state   "1" = slid towards game, "0" = slid back
 *   sys.rm.slider.event   counter, bumped on every state CHANGE
 *
 * This class turns that into an action. The daemon deliberately does not launch anything itself —
 * it has no business knowing about packages, and a coredomain daemon starting activities would be
 * a much bigger sepolicy surface. Policy lives here, in the app that owns the UI.
 *
 * Config (persist, so it survives reboots):
 *   persist.sys.rm.slider.mode   0 = do nothing (default)
 *                                1 = launch an app
 *                                2 = launch an app on ON, and return home on OFF
 *                                3 = torch: light on when slid over, off when slid back
 *   persist.sys.rm.slider.app    package name for modes 1/2
 *
 * We trigger on the .event counter rather than on .state, so sliding to the same position twice
 * (or a re-read after a daemon restart) cannot double-fire. The daemon already de-duplicates
 * repeated EV_SW reports, so one physical slide = one increment = one action.
 *
 * Delivery: the daemon also pushes every slide down /dev/socket/slider_uewake, and a thread here
 * blocks on it. That is what makes the torch work with the screen OFF: a property poll only
 * runs while the SoC is awake, and after a screen-off slide the SoC (woken by the slider GPIO)
 * was back in suspend before the next 400 ms tick -- so the light came on at the next unrelated
 * wakeup, i.e. when the user tapped the screen. A blocked socket read is woken by the kernel
 * the moment the daemon writes, the daemon holds a timed kernel wake lock across the hand-off,
 * and we hold a partial wake lock while acting. The property poll stays only as a slow safety
 * net for a build where the socket is missing.
 */
final class SliderWatcher {

    private static final String TAG = "RMControl";

    static final String PROP_STATE = "sys.rm.slider.state";
    static final String PROP_EVENT = "sys.rm.slider.event";
    static final String PROP_MODE  = "persist.sys.rm.slider.mode";
    static final String PROP_APP   = "persist.sys.rm.slider.app";

    static final int MODE_NOTHING   = 0;
    static final int MODE_LAUNCH    = 1;
    static final int MODE_LAUNCH_HOME = 2;
    /**
     * Torch. Requested on XDA by a user whose only use for the slider on stock was the flashlight
     * -- and who could not bind it in Key Mapper, because the slider is an input SWITCH (EV_SW)
     * and has no keycode for a key-mapper to see. A switch maps onto a torch naturally: slid over
     * = lit, slid back = dark, and the physical position always matches the light.
     */
    static final int MODE_TORCH     = 3;

    /**
     * Emit a configurable key code on each slide (XDA #337, NX123Dos), so key-remapper apps and
     * automation tools have something to bind to.
     *
     * <p>The injection itself is done by the slider_uewake daemon, not here: a remapper has to see
     * a real input device, and only the daemon can open /dev/uinput. This class deliberately takes
     * no action for this mode -- it is listed only so the Settings UI and the daemon agree on the
     * number. Key codes are LINUX input codes (KEY_F13 = 183 ...), not Android key codes.
     */
    static final int MODE_KEYCODE   = 4;

    static final String PROP_KEY_ON  = "persist.sys.rm.slider.key_on";
    static final String PROP_KEY_OFF = "persist.sys.rm.slider.key_off";

    private final Context mCtx;
    private final PowerManager.WakeLock mWake;
    private String mLastEvent;
    /** One-shot proof that the property callback is actually being delivered to this process. */
    private boolean mSawCallback;

    private SliderWatcher(Context ctx) {
        mCtx = ctx.getApplicationContext();
        final PowerManager pm = mCtx.getSystemService(PowerManager.class);
        mWake = pm == null ? null
                : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RMControl:slider");
        if (mWake != null) mWake.setReferenceCounted(false);
        // Seed from the current counter so we do not fire for the state the daemon published at
        // boot — the user has not touched the slider yet at that point.
        mLastEvent = Prop.get(PROP_EVENT, "");
    }

    /**
     * Fallback poll period. The socket below is the real delivery path; this only catches a
     * build where the daemon has no socket. A property read is a shared-memory read, not IPC.
     */
    private static final long POLL_MS = 2000L;

    /** init-created push socket, see slider_uewake.rc. */
    private static final String SOCKET = "slider_uewake";
    /** Upper bound on how long a slide's action may keep the SoC awake. */
    private static final long ACTION_WAKE_MS = 3000L;

    static void start(Context ctx) {
        final SliderWatcher w = new SliderWatcher(ctx);

        // SystemProperties.addChangeCallback() is registered as a best-effort fast path, but it is
        // NOT relied on: on this device it never delivered a single callback to this process --
        // verified 2026-08-20 with ten physical slides, where slider_uewake logged every one and
        // the callback fired zero times. The poll below is what actually makes the feature work.
        try {
            SystemProperties.addChangeCallback(w::onPropertiesChanged);
        } catch (Throwable t) {
            Log.e(TAG, "addChangeCallback unavailable; polling only", t);
        }

        final Thread sock = new Thread(w::socketLoop, "slider-sock");
        sock.setDaemon(true);
        sock.start();

        final HandlerThread th = new HandlerThread("slider-poll");
        th.start();
        final Handler h = new Handler(th.getLooper());
        h.post(new Runnable() {
            private boolean mFirst = true;
            @Override public void run() {
                if (mFirst) {
                    mFirst = false;
                    // Logged from the poll thread, not from Application.onCreate: a persistent app
                    // starts before logd is accepting, so anything logged there is silently lost.
                    Log.i(TAG, "slider watcher polling (seq='" + w.mLastEvent + "' mode=" + getMode()
                            + " app='" + Prop.get(PROP_APP, "") + "')");
                }
                w.onPropertiesChanged();
                h.postDelayed(this, POLL_MS);
            }
        });
    }

    /**
     * Blocks on the daemon's push socket forever; reconnects with backoff. The daemon is off
     * (connection refused) whenever persist.sys.rm.slider.enabled is 0, so the retry is capped
     * at 30 s -- a refused unix connect costs microseconds, and the poll covers the gap.
     */
    private void socketLoop() {
        long backoff = 1000L;
        for (;;) {
            try (LocalSocket ls = new LocalSocket()) {
                ls.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.RESERVED));
                Log.i(TAG, "slider socket connected");
                backoff = 1000L;
                final BufferedReader in =
                        new BufferedReader(new InputStreamReader(ls.getInputStream()), 64);
                String line;
                while ((line = in.readLine()) != null) {
                    // "<state> <seq>". We act through the same property path as the poll so
                    // both routes share one de-duplicating sequence check; the line itself is
                    // only the wake-up. The daemon sets the properties BEFORE it writes the
                    // socket, so they are already current here.
                    withWakeLock(this::onPropertiesChanged);
                }
                Log.w(TAG, "slider socket closed by daemon");
            } catch (Throwable t) {
                // Quiet on the expected case (daemon disabled -> ECONNREFUSED) after the first.
                if (backoff == 1000L) Log.w(TAG, "slider socket: " + t);
            }
            try { Thread.sleep(backoff); } catch (InterruptedException e) { return; }
            backoff = Math.min(backoff * 2, 30000L);
        }
    }

    private void withWakeLock(Runnable r) {
        if (mWake != null) mWake.acquire(ACTION_WAKE_MS);
        try {
            r.run();
        } finally {
            if (mWake != null && mWake.isHeld()) mWake.release();
        }
    }

    // synchronized: reachable from both the (best-effort) property callback thread and the poll
    // thread. Without it, two threads could pass the counter check for the same slide and fire the
    // action twice.
    private synchronized void onPropertiesChanged() {
        if (!mSawCallback) {
            mSawCallback = true;
            // If this never appears, the property-change callback is not reaching us at all and
            // the fault is the transport, not the slider logic.
            Log.i(TAG, "property callback is live");
        }

        final String ev = Prop.get(PROP_EVENT, "");
        if (ev.isEmpty() || ev.equals(mLastEvent)) return;   // not us, or nothing new
        mLastEvent = ev;

        final boolean on = "1".equals(Prop.get(PROP_STATE, "0"));
        final int mode = getMode();
        // Logged BEFORE the mode check: a slide that lands here with mode=0 means the UI never
        // wrote the setting, which looks identical to "the slider does nothing" from outside.
        Log.i(TAG, "slider event seq=" + ev + " state=" + (on ? 1 : 0) + " mode=" + mode);
        if (mode == MODE_NOTHING) return;

        if (mode == MODE_TORCH) {
            setTorch(on);
            return;
        }

        // Handled entirely by slider_uewake (it owns /dev/uinput). Acting here as well would
        // double-fire the slide.
        if (mode == MODE_KEYCODE) return;

        if (on) {
            launchConfiguredApp();
        } else if (mode == MODE_LAUNCH_HOME) {
            goHome();
        }
    }

    private static int getMode() {
        try { return Integer.parseInt(Prop.get(PROP_MODE, "0").trim()); }
        catch (NumberFormatException e) { return MODE_NOTHING; }
    }

    private void launchConfiguredApp() {
        final String pkg = Prop.get(PROP_APP, "").trim();
        if (pkg.isEmpty()) {
            Log.w(TAG, "slider: mode set but " + PROP_APP + " is empty");
            return;
        }
        final Intent i = mCtx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            Log.w(TAG, "slider: no launch intent for " + pkg + " (not installed?)");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            mCtx.startActivity(i);
            Log.i(TAG, "slider: launched " + pkg);
        } catch (Throwable t) {
            Log.e(TAG, "slider: failed to launch " + pkg, t);
        }
    }

    /** Drive the flash unit directly; no camera session and no permission needed for torch mode. */
    private void setTorch(boolean on) {
        final CameraManager cm = mCtx.getSystemService(CameraManager.class);
        if (cm == null) return;
        try {
            for (String id : cm.getCameraIdList()) {
                final Boolean hasFlash =
                        cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(hasFlash)) {
                    cm.setTorchMode(id, on);
                    Log.i(TAG, "slider: torch " + (on ? "on" : "off") + " via camera " + id);
                    return;
                }
            }
            Log.w(TAG, "slider: no camera reports a flash unit");
        } catch (Throwable t) {
            // Another app holding the camera can make this throw; that is not fatal.
            Log.e(TAG, "slider: could not set torch", t);
        }
    }

    private void goHome() {
        final Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mCtx.startActivity(home);
        } catch (Throwable t) {
            Log.e(TAG, "slider: failed to go home", t);
        }
    }
}
