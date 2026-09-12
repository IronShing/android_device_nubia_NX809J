/*
 * SPDX-License-Identifier: Apache-2.0
 * Tracks Android Auto (car mode) so Privacy Guard can lift mic/location for the
 * apps the user marked "allow during Android Auto". Android Auto projection puts
 * the phone into UI_MODE_TYPE_CAR and broadcasts ACTION_ENTER/EXIT_CAR_MODE; we
 * mirror that into PrivacyGuard.setAaConnected(), which republishes the guard so
 * the exemption applies on connect and is withdrawn on disconnect.
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

final class CarModeWatcher {

    static void start(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final UiModeManager um = app.getSystemService(UiModeManager.class);
        // Seed with the current mode (the phone may already be projecting).
        PrivacyGuard.setAaConnected(app,
                um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR);

        final IntentFilter f = new IntentFilter();
        f.addAction(UiModeManager.ACTION_ENTER_CAR_MODE);
        f.addAction(UiModeManager.ACTION_EXIT_CAR_MODE);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                PrivacyGuard.setAaConnected(app,
                        UiModeManager.ACTION_ENTER_CAR_MODE.equals(i.getAction()));
            }
        }, f, Context.RECEIVER_EXPORTED /* protected system broadcast */);
    }

    private CarModeWatcher() {}
}
