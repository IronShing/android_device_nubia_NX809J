/*
 * SPDX-License-Identifier: Apache-2.0
 * Tracks Android Auto so Privacy Guard can lift mic/location for the apps the
 * user marked "allow during Android Auto". Two signals, OR-ed:
 *  - automotive projection: gearhead calls UiModeManager.requestProjection(
 *    PROJECTION_TYPE_AUTOMOTIVE) for the whole head-unit session (this is what
 *    Telecom keys its car-mode dialer on). Read with READ_PROJECTION_STATE
 *    (plain signature, platform cert grants it, no privapp entry needed).
 *  - car mode (UI_MODE_TYPE_CAR / ACTION_ENTER|EXIT_CAR_MODE): Assistant driving
 *    mode and older AA builds. Car mode alone was NOT enough: an AA 17.x session on
 *    2026-09-16 never entered car mode, the mic stayed guarded and Gemini heard
 *    silence until the user hit a timed allow.
 * Either becoming true mirrors into PrivacyGuard.setAaConnected(), which
 * republishes the guard so the exemption applies on connect and is withdrawn on
 * disconnect.
 *
 * Scoped to the Google app in practice (that's what AA asks the mic from) via the
 * per-app opt-in flag; car mode is only the gate. A no-op cost when no app is
 * AA-allowed (apply() writes nothing when the effective rule string is unchanged).
 */
package com.nubia.rmcontrol;

import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.util.Log;

final class CarModeWatcher {

    private static final String TAG = "RMControl.CarMode";

    private static boolean sCarMode;
    private static boolean sProjecting;

    private static void publish(Context app) {
        PrivacyGuard.setAaConnected(app, sCarMode || sProjecting);
    }

    static void start(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final UiModeManager um = app.getSystemService(UiModeManager.class);
        if (um == null) return;

        // Seed with the current state (the phone may already be projecting).
        sCarMode = um.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR;
        try {
            sProjecting = (um.getActiveProjectionTypes()
                    & UiModeManager.PROJECTION_TYPE_AUTOMOTIVE) != 0;
            um.addOnProjectionStateChangedListener(UiModeManager.PROJECTION_TYPE_AUTOMOTIVE,
                    app.getMainExecutor(), (type, pkgs) -> {
                        sProjecting = pkgs != null && !pkgs.isEmpty();
                        Log.i(TAG, "automotive projection " + (sProjecting ? "by " + pkgs : "ended"));
                        publish(app);
                    });
        } catch (RuntimeException e) {
            Log.w(TAG, "projection state unavailable, car mode only", e);
        }
        publish(app);

        final IntentFilter f = new IntentFilter();
        f.addAction(UiModeManager.ACTION_ENTER_CAR_MODE);
        f.addAction(UiModeManager.ACTION_EXIT_CAR_MODE);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                sCarMode = UiModeManager.ACTION_ENTER_CAR_MODE.equals(i.getAction());
                Log.i(TAG, "car mode " + (sCarMode ? "entered" : "exited"));
                publish(app);
            }
        }, f, Context.RECEIVER_EXPORTED /* protected system broadcast */);
    }

    private CarModeWatcher() {}
}
