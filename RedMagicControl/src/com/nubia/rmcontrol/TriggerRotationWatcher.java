/*
 * SPDX-License-Identifier: Apache-2.0
 * Publishes the internal display's orientation to sys.rm.trig_rot (0 = portrait,
 * 1 = landscape) so the trigger_map daemon can select the matching per-orientation
 * touch-target set (portrait vs landscape). sys.rm.* is the same rm_ctrl_prop
 * context RedMagic Control already writes, and it is NOT a persist prop, so this
 * updates on every rotation without any flash wear. Event-driven (DisplayListener),
 * no polling; a no-op cost for users who never map the triggers.
 */
package com.nubia.rmcontrol;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Surface;

final class TriggerRotationWatcher {

    static void start(Context ctx) {
        DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        if (dm == null) return;
        publish(dm);   // seed the current value; rotation may not change for a while
        dm.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {}
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY) publish(dm);
            }
        }, null /* run on the calling (main) thread's Looper */);
    }

    private static void publish(DisplayManager dm) {
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return;
        int r = d.getRotation();
        boolean land = (r == Surface.ROTATION_90 || r == Surface.ROTATION_270);
        Prop.set("sys.rm.trig_rot", land ? "1" : "0");
    }

    private TriggerRotationWatcher() {}
}
