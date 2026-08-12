package com.nx809j.voiprecorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts detection on boot, but only once the user has opted in.
 * In "hide notification" mode there is no persistent service to start — the
 * NotificationListenerService is bound automatically by the system.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        Prefs p = new Prefs(ctx);
        if (p.isEnabled() && p.hasConsent() && !p.hideNotification()) {
            ctx.startForegroundService(new Intent(ctx, CallMonitorService.class));
        }
    }
}
