package com.nubia.rmcontrol;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.hardware.input.VirtualMouse;
import android.hardware.input.VirtualMouseButtonEvent;
import android.hardware.input.VirtualMouseConfig;
import android.hardware.input.VirtualMouseRelativeEvent;
import android.hardware.input.VirtualMouseScrollEvent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * "Use the phone as a touchpad" (DeX-style) for an external display: the phone panel goes
 * black and every touch drives a mouse pointer on the connected screen. Meant for XR glasses
 * and TVs, where the user wants to start a video with the pointer and then not look at the
 * phone at all.
 *
 * <p>How the pointer is produced (2026-09-05, see memory nx809j_phone_touchpad_mode):
 * {@link InputManager#createVirtualMouse} opens a real uinput mouse inside system_server,
 * associated with the external display (InputManagerService.checkDisplayAssociationPermission
 * passes because this app runs as android.uid.system; INJECT_EVENTS is a plain signature
 * permission, so no privapp allowlist entry). The device then goes through the ordinary
 * InputReader → CursorInputMapper → PointerChoreographer path, which is what draws and moves
 * the pointer sprite on that display. Injecting SOURCE_MOUSE MotionEvents would deliver clicks
 * but never show a pointer (injection bypasses PointerChoreographer), so it is not used.
 *
 * <p>Gestures: one finger moves the pointer; tap = click; double-tap-and-hold = drag; long
 * press = right click; two fingers = scroll (two-finger tap = right click); three-finger tap =
 * phone panel off, monitor stays on ({@link MonitorOnlyService#sleepPhone}; power button or DT2W
 * brings the touchpad back). Back exits, as does unplugging the screen or turning the phone
 * screen off any other way.
 *
 * <p>The phone panel is not turned off — a full-screen black window on the OLED is unlit, and
 * the window asks for the panel's minimum backlight (screenBrightness 0 is clamped to the
 * panel minimum by DisplayPowerController, it is never "off"). Keeping the panel technically on
 * is also what keeps this window receiving touches. The external display is held awake with a
 * display-group wake lock; on this ROM an external display lives in its own display group
 * (config_canInternalDisplayHostDesktops=false → REASON_PROJECTED), so it stays on regardless of
 * the phone's own timeout.
 */
public class TouchpadActivity extends Activity {
    private static final String TAG = "RmTouchpad";

    /** Pointer speed multiplier, 1..10 (5 = 1.0x). */
    static final String PROP_SPEED = "persist.sys.rm.touchpad.speed";
    /** Natural (content follows fingers) vs. classic scrolling. */
    static final String PROP_NATURAL_SCROLL = "persist.sys.rm.touchpad.natural";

    private static final long TAP_MS = 220;
    private static final long DOUBLE_TAP_MS = 300;
    private static final long LONG_PRESS_MS = 550;
    private static final long THREE_FINGER_TAP_MS = 400;
    /** Finger pixels per scroll detent. */
    private static final float SCROLL_PX_PER_DETENT = 48f;

    private HandlerThread mThread;
    private Handler mBg;
    private VirtualMouse mMouse;
    private PowerManager.WakeLock mExtWake;
    private int mExtDisplayId = Display.INVALID_DISPLAY;
    private TextView mHint;
    /** We put the phone panel to sleep ourselves: the resulting onStop must not end the mode. */
    private boolean mSelfSleep;
    /** uptime of the last return from self-sleep; three-finger taps right after are ignored. */
    private long mWokeAt;

    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayChanged(int id) {}
        @Override public void onDisplayRemoved(int id) {
            if (id == mExtDisplayId) {
                Log.i(TAG, "external display " + id + " removed, leaving touchpad mode");
                if (mSelfSleep) MonitorOnlyService.wakePhone();   // do not leave everything dark
                finish();
            }
        }
    };

    static int externalDisplayId(Context ctx) {
        final DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        if (dm == null) return Display.INVALID_DISPLAY;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.getType() == Display.TYPE_EXTERNAL
                    && d.getState() != Display.STATE_OFF) {
                return d.getDisplayId();
            }
        }
        return Display.INVALID_DISPLAY;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mExtDisplayId = externalDisplayId(this);
        if (mExtDisplayId == Display.INVALID_DISPLAY) {
            Toast.makeText(this, "Connect an external screen first", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0f;   // panel minimum; black pixels are unlit on the OLED anyway
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        final Pad pad = new Pad(this);
        root.addView(pad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mHint = new TextView(this);
        mHint.setTextColor(0xFF444444);
        mHint.setTextSize(14);
        mHint.setGravity(Gravity.CENTER);
        mHint.setText("Touchpad for the external screen\n\n"
                + "one finger: move · tap: click · double-tap and hold: drag\n"
                + "long press: right click · two fingers: scroll\n"
                + "three-finger tap: phone screen off, monitor stays on\n"
                + "(power button or double-tap brings it back)\n\n"
                + "Back (swipe in from a side edge) leaves");
        final FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.CENTER;
        root.addView(mHint, hlp);
        setContentView(root);
        // After setContentView: the insets controller lives on the DecorView, which does not
        // exist before the content view is installed (NPE otherwise).
        getWindow().setDecorFitsSystemWindows(false);
        final WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            ic.hide(WindowInsets.Type.systemBars());
            ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        // The hint is only for the first seconds; after that the panel is fully black.
        mHint.postDelayed(() -> mHint.animate().alpha(0f).setDuration(1500).start(), 4000);

        mThread = new HandlerThread("rm-touchpad");
        mThread.start();
        mBg = new Handler(mThread.getLooper());
        // Binder calls into InputManagerService stay off the UI thread; the mouse is also fed
        // from here so a burst of moves never blocks touch delivery.
        mBg.post(this::openMouse);

        final DisplayManager dm = getSystemService(DisplayManager.class);
        if (dm != null) dm.registerDisplayListener(mDisplayListener, null);
    }

    private void openMouse() {
        try {
            final InputManager im = getSystemService(InputManager.class);
            final VirtualMouseConfig cfg = new VirtualMouseConfig.Builder()
                    .setInputDeviceName("RedMagic Touchpad")
                    .setAssociatedDisplayId(mExtDisplayId)
                    .build();
            mMouse = im.createVirtualMouse(cfg);
            // Nudge so the pointer sprite becomes visible right away.
            mMouse.sendRelativeEvent(new VirtualMouseRelativeEvent.Builder()
                    .setRelativeX(1f).setRelativeY(0f).build());
            Log.i(TAG, "virtual mouse open on display " + mExtDisplayId);
        } catch (Throwable t) {
            Log.e(TAG, "createVirtualMouse failed", t);
            runOnUiThread(() -> {
                Toast.makeText(this, "Touchpad unavailable: " + t.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            });
            return;
        }
        try {
            final PowerManager pm = getSystemService(PowerManager.class);
            // Display-scoped (hidden 3-arg) wake lock: keeps the external display's own power
            // group awake without touching the phone's.
            mExtWake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "rmcontrol:touchpad", mExtDisplayId);
            mExtWake.acquire();
        } catch (Throwable t) {
            Log.w(TAG, "external wake lock", t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mSelfSleep) {
            // Back from "monitor only": the panel is on again, the touchpad simply continues.
            mSelfSleep = false;
            mWokeAt = SystemClock.uptimeMillis();
            mHint.setAlpha(1f);
            mHint.postDelayed(() -> mHint.animate().alpha(0f).setDuration(1500).start(), 2500);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Screen off, another app on top, or the user left: never linger as an invisible
        // black window that eats touches. Exception: we switched the panel off ourselves
        // (three-finger tap); then stay resident so waking the phone lands back here.
        if (!mSelfSleep) finish();
    }

    /** Three-finger tap: phone panel off, external display (and this mode) stay up. */
    private void monitorOnly() {
        if (mSelfSleep) return;
        // fingers still resting on the panel after a wake gesture must not put it straight back
        if (SystemClock.uptimeMillis() - mWokeAt < 800) return;
        mSelfSleep = true;
        Log.i(TAG, "three-finger tap: sleeping the phone display only");
        if (!MonitorOnlyService.sleepPhone()) {
            mSelfSleep = false;
            Toast.makeText(this, "Could not turn the phone screen off", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        final DisplayManager dm = getSystemService(DisplayManager.class);
        if (dm != null) dm.unregisterDisplayListener(mDisplayListener);
        if (mBg != null) {
            mBg.post(() -> {
                try { if (mExtWake != null && mExtWake.isHeld()) mExtWake.release(); } catch (Throwable ignored) {}
                try { if (mMouse != null) mMouse.close(); } catch (Throwable ignored) {}
                mMouse = null;
                mThread.quitSafely();
            });
        }
    }

    // ---- mouse primitives (background thread) ----

    private float mFracX, mFracY;

    private void move(float dx, float dy) {
        if (mBg == null) return;
        mBg.post(() -> {
            final VirtualMouse m = mMouse;
            if (m == null) return;
            // uinput REL_X/REL_Y are integers: keep the sub-pixel remainder so slow, precise
            // movement is not swallowed.
            mFracX += dx; mFracY += dy;
            final int ix = (int) mFracX, iy = (int) mFracY;
            mFracX -= ix; mFracY -= iy;
            if (ix == 0 && iy == 0) return;
            m.sendRelativeEvent(new VirtualMouseRelativeEvent.Builder()
                    .setRelativeX(ix).setRelativeY(iy).build());
        });
    }

    private void button(int code, boolean press) {
        if (mBg == null) return;
        mBg.post(() -> {
            final VirtualMouse m = mMouse;
            if (m == null) return;
            m.sendButtonEvent(new VirtualMouseButtonEvent.Builder()
                    .setButtonCode(code)
                    .setAction(press ? VirtualMouseButtonEvent.ACTION_BUTTON_PRESS
                                     : VirtualMouseButtonEvent.ACTION_BUTTON_RELEASE)
                    .build());
        });
    }

    private void click(int code) {
        button(code, true);
        button(code, false);
    }

    /** x/y in detents, each clamped to [-1, 1] per event (high-res wheel units). */
    private void scroll(float x, float y) {
        if (mBg == null) return;
        mBg.post(() -> {
            final VirtualMouse m = mMouse;
            if (m == null) return;
            m.sendScrollEvent(new VirtualMouseScrollEvent.Builder()
                    .setXAxisMovement(Math.max(-1f, Math.min(1f, x)))
                    .setYAxisMovement(Math.max(-1f, Math.min(1f, y)))
                    .build());
        });
    }

    private static float speedGain() {
        int s = 5;
        try { s = Integer.parseInt(Prop.get(PROP_SPEED, "5").trim()); } catch (NumberFormatException ignored) {}
        s = Math.max(1, Math.min(10, s));
        // 1 → 0.4x … 5 → 1.0x … 10 → 2.5x
        return s <= 5 ? 0.4f + 0.15f * (s - 1) : 1.0f + 0.3f * (s - 5);
    }

    // ---- gesture recogniser ----

    private final class Pad extends View {
        private final float mSlop;
        private final float mDensityScale;
        private boolean mDragging;          // double-tap-and-hold: primary held while moving
        private boolean mMoved;
        private boolean mTwoFinger;
        private boolean mTwoFingerMoved;
        private boolean mThreeFinger;
        private boolean mLongPressed;
        private long mDownAt;
        private long mLastTapAt;
        private float mDownX, mDownY;
        private float mLastX, mLastY;
        private float mScrollAccX, mScrollAccY;
        private float mLast2X, mLast2Y;     // centroid of two fingers
        private final Runnable mLongPress = () -> {
            if (mMoved || mTwoFinger || mThreeFinger || mDragging) return;
            mLongPressed = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            click(VirtualMouseButtonEvent.BUTTON_SECONDARY);
        };

        Pad(Context ctx) {
            super(ctx);
            mSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
            // Phone panel is ~430 dpi vs. ~160 on the monitor: without this the pointer would
            // crawl. Base gain maps finger mm to pointer px roughly like a laptop touchpad.
            mDensityScale = 160f / getResources().getDisplayMetrics().densityDpi * 2.2f;
            setClickable(true);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            final long now = SystemClock.uptimeMillis();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownAt = now;
                    mDownX = mLastX = e.getX(); mDownY = mLastY = e.getY();
                    mMoved = false; mTwoFinger = false; mTwoFingerMoved = false; mLongPressed = false;
                    mDragging = false; mThreeFinger = false;
                    if (now - mLastTapAt < DOUBLE_TAP_MS) {
                        // second tap of a double tap: hold the button until the finger lifts
                        mDragging = true;
                        mLastTapAt = 0;
                        button(VirtualMouseButtonEvent.BUTTON_PRIMARY, true);
                    } else {
                        postDelayed(mLongPress, LONG_PRESS_MS);
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() >= 3) {
                        removeCallbacks(mLongPress);
                        mThreeFinger = true;
                        mTwoFinger = false;
                        if (mDragging) { button(VirtualMouseButtonEvent.BUTTON_PRIMARY, false); mDragging = false; }
                        return true;
                    }
                    if (e.getPointerCount() == 2 && !mDragging) {
                        removeCallbacks(mLongPress);
                        mTwoFinger = true;
                        mScrollAccX = mScrollAccY = 0;
                        mLast2X = (e.getX(0) + e.getX(1)) / 2f;
                        mLast2Y = (e.getY(0) + e.getY(1)) / 2f;
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (mThreeFinger) return true;
                    if (mTwoFinger && e.getPointerCount() >= 2) {
                        final float cx = (e.getX(0) + e.getX(1)) / 2f;
                        final float cy = (e.getY(0) + e.getY(1)) / 2f;
                        final float dx = cx - mLast2X, dy = cy - mLast2Y;
                        mLast2X = cx; mLast2Y = cy;
                        if (!mTwoFingerMoved && Math.hypot(cx - mDownX, cy - mDownY) < mSlop) return true;
                        mTwoFingerMoved = true;
                        final float sign = Prop.getBool(PROP_NATURAL_SCROLL, true) ? 1f : -1f;
                        mScrollAccX += sign * dx / SCROLL_PX_PER_DETENT;
                        mScrollAccY += sign * dy / SCROLL_PX_PER_DETENT;
                        // emit in <=1-detent steps so nothing is clamped away
                        while (Math.abs(mScrollAccX) >= 0.05f || Math.abs(mScrollAccY) >= 0.05f) {
                            final float sx = Math.max(-1f, Math.min(1f, mScrollAccX));
                            final float sy = Math.max(-1f, Math.min(1f, mScrollAccY));
                            scroll(sx, sy);
                            mScrollAccX -= sx; mScrollAccY -= sy;
                        }
                        return true;
                    }
                    if (e.getPointerCount() != 1) return true;
                    final float x = e.getX(), y = e.getY();
                    if (!mMoved && Math.hypot(x - mDownX, y - mDownY) < mSlop) return true;
                    if (!mMoved) {
                        mMoved = true;
                        removeCallbacks(mLongPress);
                        mLastX = x; mLastY = y;   // swallow the slop distance
                        return true;
                    }
                    float dx = (x - mLastX), dy = (y - mLastY);
                    mLastX = x; mLastY = y;
                    // mild acceleration: fast flicks travel further, slow moves stay precise
                    final float v = (float) Math.hypot(dx, dy);
                    final float accel = 1f + Math.min(1.5f, v / 40f);
                    final float g = mDensityScale * speedGain() * accel;
                    move(dx * g, dy * g);
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    if (mThreeFinger) return true;
                    if (mTwoFinger && e.getPointerCount() == 2) {
                        if (!mTwoFingerMoved && now - mDownAt < TAP_MS + 100) {
                            click(VirtualMouseButtonEvent.BUTTON_SECONDARY);
                        }
                        // stay in two-finger mode until the last finger lifts, so the leftover
                        // finger does not move the pointer
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    removeCallbacks(mLongPress);
                    if (mThreeFinger) {
                        mThreeFinger = false; mTwoFinger = false;
                        if (now - mDownAt < THREE_FINGER_TAP_MS) {
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                            monitorOnly();
                        }
                        return true;
                    }
                    if (mDragging) {
                        button(VirtualMouseButtonEvent.BUTTON_PRIMARY, false);
                        mDragging = false;
                    } else if (!mTwoFinger && !mMoved && !mLongPressed && now - mDownAt < TAP_MS) {
                        click(VirtualMouseButtonEvent.BUTTON_PRIMARY);
                        mLastTapAt = now;
                    }
                    mTwoFinger = false;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(mLongPress);
                    if (mDragging) button(VirtualMouseButtonEvent.BUTTON_PRIMARY, false);
                    mDragging = false; mTwoFinger = false; mThreeFinger = false;
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
