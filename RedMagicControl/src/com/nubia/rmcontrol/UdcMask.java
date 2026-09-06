package com.nubia.rmcontrol;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.util.ArraySet;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import java.util.Set;

/**
 * Under-display front camera mask.
 *
 * The selfie sensor sits behind the OLED. While a camera app is streaming from it, the panel
 * pixels above the sensor must be black or their light floods the sensor (haze / flare in the
 * preview). Stock RedMagicOS does this in two places: NubiaCamera blanks the spot itself, and for
 * every other app services.jar (CameraServiceProxyZteHook) writes Settings.System
 * front_camera_work, which stock SystemUI (FakeNotchFeature) turns into a black disc drawn over
 * the status bar. Neither exists in AOSP-based ROMs, so third-party apps (WebRTC, video calls,
 * QR scanners) got the flare that XDA #79 reported.
 *
 * This is the SystemUI half: cameraserver tells us (CAMERA_OPEN_CLOSE_LISTENER) which package
 * opened which camera; if it is the front sensor and the app does not mask itself, a black disc
 * is drawn in a secure system overlay at the sensor's physical position.
 *
 * Geometry is stock's: layout/fake_notch_anim = 120x120 px view, top-centre, marginTop 10 px,
 * drawable/black_circle = disc r=42 centred in it -> centre (608, 70) px, r=42 px on the
 * 1216x2688 panel. Stored in panel (rotation 0) coordinates and rotated with the display.
 */
final class UdcMask {

    private static final String TAG = "RMControl.UdcMask";
    private static final String OPT_OUT_PROP = "persist.sys.rm.udcmask";

    private static final int PANEL_W = 1216;
    private static final int PANEL_H = 2688;
    private static final float CX = 608f;
    private static final float CY = 70f;
    // Stock uses r=42; measured on the panel (white screen, front-cam frame luminance): no disc 185,
    // r=42 -> 93, r=50..55 -> 88..90, dark status bar baseline 86. 48 adds tolerance at no cost.
    private static final float RADIUS = 48f;

    /** Apps that blank the sensor spot themselves (stock's ZTE_PACKAGE_NAME list). */
    private static final Set<String> SELF_MASKING = Set.of("com.android.camera", "com.zte.camera");

    private final Context mCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final CameraManager mCm;
    private final Set<String> mOpenFront = new ArraySet<>();
    private final ArraySet<String> mFrontIds = new ArraySet<>();
    private final ArraySet<String> mCheckedIds = new ArraySet<>();
    private View mView;

    private final CameraManager.AvailabilityCallback mCb = new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraOpened(String cameraId, String packageId) {
            if (!isFront(cameraId)) return;
            if (SELF_MASKING.contains(packageId)) return;
            if (!Prop.getBool(OPT_OUT_PROP, true)) return;
            Log.i(TAG, "front camera " + cameraId + " opened by " + packageId + " -> mask on");
            mOpenFront.add(cameraId);
            show();
        }

        @Override
        public void onCameraClosed(String cameraId) {
            if (!mOpenFront.remove(cameraId) || !mOpenFront.isEmpty()) return;
            Log.i(TAG, "front camera " + cameraId + " closed -> mask off");
            hide();
        }
    };

    private UdcMask(Context ctx) {
        mCtx = ctx;
        mCm = ctx.getSystemService(CameraManager.class);
    }

    static void start(Context ctx) {
        final UdcMask m = new UdcMask(ctx);
        if (m.mCm == null) {
            Log.e(TAG, "no CameraManager; UDC mask disabled");
            return;
        }
        try {
            m.mCm.registerAvailabilityCallback(m.mCb, m.mMain);
        } catch (Throwable t) {
            Log.e(TAG, "registerAvailabilityCallback failed", t);
        }
    }

    private boolean isFront(String id) {
        if (mCheckedIds.contains(id)) return mFrontIds.contains(id);
        mCheckedIds.add(id);
        try {
            final Integer facing = mCm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) mFrontIds.add(id);
        } catch (Throwable t) {
            Log.w(TAG, "characteristics for camera " + id + " unavailable", t);
        }
        return mFrontIds.contains(id);
    }

    private void show() {
        if (mView != null) return;
        final WindowManager wm = mCtx.getSystemService(WindowManager.class);
        if (wm == null) return;
        mView = new MaskView(mCtx);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("rmcontrol:udcmask");
        try {
            wm.addView(mView, lp);
        } catch (Throwable t) {
            Log.e(TAG, "addView failed", t);
            mView = null;
        }
    }

    private void hide() {
        if (mView == null) return;
        final WindowManager wm = mCtx.getSystemService(WindowManager.class);
        try {
            if (wm != null) wm.removeViewImmediate(mView);
        } catch (Throwable t) {
            Log.e(TAG, "removeView failed", t);
        }
        mView = null;
    }

    /** Transparent full-display view that paints the disc at the sensor's physical position. */
    private static final class MaskView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        MaskView(Context ctx) {
            super(ctx);
            mPaint.setColor(Color.BLACK);
            mPaint.setStyle(Paint.Style.FILL);
            setForceDarkAllowed(false);
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final Display d = getDisplay();
            final int rot = d == null ? Surface.ROTATION_0 : d.getRotation();
            // Panel (rotation 0) -> current logical coordinates. The window is full-display, so
            // logical (0,0) is the top-left corner of the rotated screen.
            final float x, y;
            switch (rot) {
                case Surface.ROTATION_90:  x = CY;           y = PANEL_W - CX; break;
                case Surface.ROTATION_180: x = PANEL_W - CX; y = PANEL_H - CY; break;
                case Surface.ROTATION_270: x = PANEL_H - CY; y = CX;           break;
                default:                   x = CX;           y = CY;           break;
            }
            canvas.drawCircle(x, y, RADIUS, mPaint);
        }
    }
}
