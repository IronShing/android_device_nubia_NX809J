/*
 * SPDX-License-Identifier: Apache-2.0
 * NX809J shoulder-trigger touch-position picker.
 *
 * Full-screen positioner for the L/R targets the trigger_map daemon taps when a
 * shoulder trigger is pressed. Two draggable markers.
 *
 * Landscape + portrait: a given on-screen button sits at a DIFFERENT physical panel
 * point depending on device rotation (the panel is physically portrait; the compositor
 * rotates it). So we store TWO coordinate sets, each in the panel's OWN (native/portrait)
 * per-mille space:
 *     portrait :  persist.sys.rm.trig_{l,r}_{x,y}
 *     landscape:  persist.sys.rm.trig_{l,r}_{x,y}_land
 * The picker converts the on-screen drag position to native-panel per-mille using the
 * current display rotation, and edits whichever set matches the orientation it is shown
 * in. Rotate the device to set the other one. The daemon injects native per-mille directly
 * and only needs a portrait/landscape bit (sys.rm.trig_rot) to choose the set.
 *
 * A full-screen picker (not a floating overlay) keeps this permission-free.
 */
package com.nubia.rmcontrol;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class TriggerMapperActivity extends Activity {

    private PickerView picker;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xCC000000);

        picker = new PickerView(this);
        root.addView(picker, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView hint = new TextView(this);
        hint.setText((picker.isLandscape()
                        ? "Editing LANDSCAPE positions."
                        : "Editing PORTRAIT positions.")
                + "\nDrag L and R to where each shoulder trigger should tap.\n"
                + "L = left trigger (F8), R = right trigger (F7).\n"
                + "Rotate the device to set the other orientation.");
        hint.setTextColor(0xFFFFFFFF);
        hint.setPadding(48, 96, 48, 0);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.TOP;
        root.addView(hint, hlp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(48, 24, 48, 96);
        Button cancel = new Button(this);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(v -> finish());
        Button save = new Button(this);
        save.setText(android.R.string.ok);
        save.setOnClickListener(v -> { picker.save(); Toast.makeText(this,
                (picker.isLandscape() ? "Landscape" : "Portrait")
                        + " trigger positions saved", Toast.LENGTH_SHORT).show(); finish(); });
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(cancel, w);
        bar.addView(save, new LinearLayout.LayoutParams(w));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bar, blp);

        setContentView(root);
    }

    /** Draws two draggable markers; loads/saves native-panel per-mille positions. */
    private final class PickerView extends View {
        private final Paint lPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float r = 64f;
        private final int rot;           // Surface.ROTATION_*
        private final boolean land;
        // On-screen (view) per-mille (0..1000). Stored/loaded values are native-panel
        // per-mille; convert on the way in/out.
        private int lxM, lyM, rxM, ryM;
        private int dragging = 0;        // 0 none, 1 L, 2 R

        PickerView(android.content.Context c) {
            super(c);
            rot = TriggerMapperActivity.this.getWindowManager()
                    .getDefaultDisplay().getRotation();
            land = (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270);
            lPaint.setColor(0xFFE53935); rPaint.setColor(0xFF1E63E9);
            txt.setColor(Color.WHITE); txt.setTextSize(44f); txt.setTextAlign(Paint.Align.CENTER);
            String sfx = land ? "_land" : "";
            // Load native-panel per-mille, convert to view space for editing.
            int[] l = nativeToView(Prop.getInt("persist.sys.rm.trig_l_x" + sfx, land ? -1 : 150),
                                   Prop.getInt("persist.sys.rm.trig_l_y" + sfx, land ? -1 : 850));
            int[] rr = nativeToView(Prop.getInt("persist.sys.rm.trig_r_x" + sfx, land ? -1 : 850),
                                    Prop.getInt("persist.sys.rm.trig_r_y" + sfx, land ? -1 : 850));
            // Sensible defaults when a set is unset (esp. the first time in landscape).
            lxM = l[0]  < 0 ? 150 : l[0];  lyM = l[1]  < 0 ? 850 : l[1];
            rxM = rr[0] < 0 ? 850 : rr[0]; ryM = rr[1] < 0 ? 850 : rr[1];
        }

        boolean isLandscape() { return land; }

        // --- rotation transform between on-screen (view) per-mille and native-panel
        // (portrait) per-mille. The panel is physically portrait; ROTATION_90/270 swap axes.
        // If landscape targets come out mirrored on device, swap the ROTATION_90 and
        // ROTATION_270 cases here (single, self-contained change).
        private int[] viewToNative(int vmx, int vmy) {
            switch (rot) {
                case Surface.ROTATION_90:  return new int[]{ vmy, 1000 - vmx };
                case Surface.ROTATION_180: return new int[]{ 1000 - vmx, 1000 - vmy };
                case Surface.ROTATION_270: return new int[]{ 1000 - vmy, vmx };
                default:                   return new int[]{ vmx, vmy };
            }
        }
        private int[] nativeToView(int nmx, int nmy) {
            if (nmx < 0 || nmy < 0) return new int[]{ -1, -1 };
            switch (rot) {
                case Surface.ROTATION_90:  return new int[]{ 1000 - nmy, nmx };
                case Surface.ROTATION_180: return new int[]{ 1000 - nmx, 1000 - nmy };
                case Surface.ROTATION_270: return new int[]{ nmy, 1000 - nmx };
                default:                   return new int[]{ nmx, nmy };
            }
        }

        private float px(int m) { return m / 1000f * getWidth(); }
        private float py(int m) { return m / 1000f * getHeight(); }

        @Override protected void onDraw(Canvas cv) {
            float lx = px(lxM), ly = py(lyM), rx = px(rxM), ry = py(ryM);
            cv.drawCircle(lx, ly, r, lPaint);
            cv.drawCircle(rx, ry, r, rPaint);
            cv.drawText("L", lx, ly + 16, txt);
            cv.drawText("R", rx, ry + 16, txt);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    float dl = (float) Math.hypot(x - px(lxM), y - py(lyM));
                    float dr = (float) Math.hypot(x - px(rxM), y - py(ryM));
                    dragging = (dl <= dr && dl < r * 2.5f) ? 1 : (dr < r * 2.5f ? 2 : 0);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging == 0 || getWidth() == 0) return true;
                    int mx = clamp((int) (x / getWidth() * 1000));
                    int my = clamp((int) (y / getHeight() * 1000));
                    if (dragging == 1) { lxM = mx; lyM = my; } else if (dragging == 2) { rxM = mx; ryM = my; }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = 0;
                    return true;
            }
            return super.onTouchEvent(e);
        }

        private int clamp(int v) { return v < 0 ? 0 : (v > 1000 ? 1000 : v); }

        void save() {
            String sfx = land ? "_land" : "";
            int[] l = viewToNative(lxM, lyM);
            int[] rr = viewToNative(rxM, ryM);
            Prop.set("persist.sys.rm.trig_l_x" + sfx, Integer.toString(l[0]));
            Prop.set("persist.sys.rm.trig_l_y" + sfx, Integer.toString(l[1]));
            Prop.set("persist.sys.rm.trig_r_x" + sfx, Integer.toString(rr[0]));
            Prop.set("persist.sys.rm.trig_r_y" + sfx, Integer.toString(rr[1]));
        }
    }
}
