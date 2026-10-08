package com.nubia.rmcontrol;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;


/**
 * Touchpad + display-off row in the collapsed shade only while an external screen (DP / XReal
 * glasses) is plugged in: sets Settings.Secure rm_qqs_extra_rows, and our SystemUI patch
 * (QuickQuickSettingsRowRepository + QuickQuickSettingsViewModel) adds a row and lifts those two
 * tiles into it. The tiles must be in the user's tile list (anywhere). User request 2026-10-03;
 * moving them in sysui_qs_tiles from here was reverted by SystemUI (verified 10-04).
 */
final class ExtDisplayTiles {
    private static final String TAG = "RmExtTiles";
    private static final String EXTRA_ROWS = "rm_qqs_extra_rows";

    private static Boolean sShown;

    static void start(Context ctx) {
        final DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        if (dm == null) return;
        final Handler h = new Handler(Looper.getMainLooper());
        dm.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { update(ctx, dm); }
            @Override public void onDisplayRemoved(int id) { update(ctx, dm); }
            @Override public void onDisplayChanged(int id) { }
        }, h);
        update(ctx, dm);
    }

    private static boolean externalPresent(DisplayManager dm) {
        // Recorders, MagicDesk and our own virtual displays are not TYPE_EXTERNAL.
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.getType() == Display.TYPE_EXTERNAL) {
                return true;
            }
        }
        return false;
    }

    private static synchronized void update(Context ctx, DisplayManager dm) {
        final boolean show = externalPresent(dm);
        if (sShown != null && sShown == show) return;
        sShown = show;
        try {
            // Only the row request. SystemUI owns sysui_qs_tiles (outside edits are reverted), so
            // SystemUI itself lifts the touchpad / display-off tiles into that row while it is on.
            Settings.Secure.putInt(ctx.getContentResolver(), EXTRA_ROWS, show ? 1 : 0);
            Log.i(TAG, (show ? "external display: extra row on" : "no external display: extra row off"));
        } catch (Exception e) {
            Log.w(TAG, "tile update failed: " + e);
        }
    }

    private ExtDisplayTiles() {}
}
