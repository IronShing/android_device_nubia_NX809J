package com.nx809j.voiprecorder;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Reads the calling app's own ongoing-call notification to learn WHO the call is with (WhatsApp,
 * Telegram, Signal... all post a CallStyle / ongoing notification titled with the contact).
 *
 * A notification listener normally stays bound and is woken for every notification on the
 * device, which is exactly the battery cost this app was rebuilt to avoid, so this one unbinds
 * itself the moment it connects unless a peek is in progress: {@link #peekTitle} rebinds on
 * demand, reads the active notifications once, and unbinds again. Between calls it costs nothing.
 */
public class CallNotifPeek extends NotificationListenerService {
    private static final String TAG = "VoipRecorder";
    private static final Object LOCK = new Object();
    private static CallNotifPeek sBound;
    private static String sWantPkg;

    @Override public void onListenerConnected() {
        synchronized (LOCK) {
            sBound = this;
            LOCK.notifyAll();
            if (sWantPkg == null) requestUnbind();   // idle: stay unbound, no wake-ups
        }
    }

    @Override public void onListenerDisconnected() {
        synchronized (LOCK) { if (sBound == this) sBound = null; }
    }

    /** Make sure the listener is enabled in Settings (we are platform-signed, so no prompt). */
    static void ensureEnabled(Context ctx) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            ComponentName cn = new ComponentName(ctx, CallNotifPeek.class);
            if (!nm.isNotificationListenerAccessGranted(cn)) {
                nm.setNotificationListenerAccessGranted(cn, true);
                Log.i(TAG, "notification peek listener enabled");
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot enable notification peek", t);
        }
    }

    /**
     * Title (contact) of pkg's call notification, or null. Blocks up to timeoutMs for the
     * rebind; call it off the main thread.
     */
    static String peekTitle(Context ctx, String pkg, long timeoutMs) {
        ensureEnabled(ctx);
        CallNotifPeek s;
        synchronized (LOCK) {
            sWantPkg = pkg;
            s = sBound;
            if (s == null) {
                try {
                    requestRebind(new ComponentName(ctx, CallNotifPeek.class));
                    LOCK.wait(timeoutMs);
                } catch (Throwable t) {
                    Log.w(TAG, "rebind failed", t);
                }
                s = sBound;
            }
        }
        String title = null;
        try {
            if (s != null) title = pick(s.getActiveNotifications(), pkg);
        } catch (Throwable t) {
            Log.w(TAG, "peek failed", t);
        } finally {
            synchronized (LOCK) {
                sWantPkg = null;
                if (sBound != null) { try { sBound.requestUnbind(); } catch (Throwable ignored) { } }
            }
        }
        return title;
    }

    private static String pick(StatusBarNotification[] sbns, String pkg) {
        if (sbns == null) return null;
        String best = null; int bestScore = -1;
        for (StatusBarNotification sbn : sbns) {
            if (!pkg.equals(sbn.getPackageName())) continue;
            Notification n = sbn.getNotification();
            Bundle ex = n.extras;
            if (ex == null) continue;
            String name = null;
            try {
                Person p = ex.getParcelable(Notification.EXTRA_CALL_PERSON, Person.class);
                if (p != null && p.getName() != null) name = p.getName().toString();
            } catch (Throwable ignored) { }
            CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = ex.getCharSequence(Notification.EXTRA_TEXT);
            if (name == null && title != null) name = title.toString();
            // "Ongoing call" as the title with the contact in the text: swap.
            if (name != null && text != null && name.toLowerCase().contains("call")
                    && !text.toString().toLowerCase().contains("call")) {
                name = text.toString();
            }
            if (name == null || name.trim().isEmpty()) continue;
            int score = 0;
            if (Notification.CATEGORY_CALL.equals(n.category)) score += 4;
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) score += 2;
            if (ex.containsKey(Notification.EXTRA_CALL_PERSON)) score += 1;
            Log.d(TAG, "notif " + pkg + " cat=" + n.category + " title=" + title + " text=" + text
                    + " -> " + name + " score " + score);
            if (score > bestScore) { bestScore = score; best = name.trim(); }
        }
        return best;
    }
}
