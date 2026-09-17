/*
 * SPDX-License-Identifier: Apache-2.0
 * Center notifications: for a user-chosen set of apps, mirror an incoming heads-up as a large
 * card in the MIDDLE of the screen (tap to open, swipe to dismiss) so it isn't missed while a
 * game / Gemini / video is fullscreen. Chosen apps only; everything else keeps the normal top
 * heads-up. The app is android:persistent so this listener stays bound.
 */
package com.nubia.rmcontrol;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class CenterNotifListener extends NotificationListenerService {
    private static final String TAG = "RMControl.CenterNotif";
    private static final String PREFS = "center_notif";
    private static final String KEY_PKGS = "pkgs";
    private static final long AUTO_DISMISS_MS = 6000;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private WindowManager mWm;
    private View mCard;                 // one at a time
    private Runnable mAutoDismiss;

    // ---- chosen-app set (shared with SettingsActivity, same package) ----------------------
    private static SharedPreferences cp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    static Set<String> apps(Context c) {
        return new HashSet<>(cp(c).getStringSet(KEY_PKGS, Collections.emptySet()));
    }
    static void setApp(Context c, String pkg, boolean on) {
        final Set<String> s = apps(c);
        if (on) s.add(pkg); else s.remove(pkg);
        cp(c).edit().putStringSet(KEY_PKGS, s).apply();
        // A listener sees every notification on the device: hold the grant only while there is
        // at least one chosen app.
        setEnabled(c, !s.isEmpty());
    }
    static void ensureEnabled(Context c) { if (!apps(c).isEmpty()) setEnabled(c, true); }

    /**
     * Grant/revoke our notification-listener access through NotificationManager
     * (MANAGE_NOTIFICATION_LISTENERS, signature-level, ours via the platform cert).
     *
     * NOT via Settings.Secure.enabled_notification_listeners: since P that setting is only read
     * once by ManagedServices.migrateToXml() on a device that has no notification_policy.xml yet;
     * approvals live in that XML afterwards and nothing observes the setting, so writing it on a
     * running device changes nothing (verified: listener never bound).
     */
    static void setEnabled(Context c, boolean on) {
        try {
            final ComponentName cn = new ComponentName(c, CenterNotifListener.class);
            final NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm.isNotificationListenerAccessGranted(cn) == on) return;
            nm.setNotificationListenerAccessGranted(cn, on);
            Log.i(TAG, (on ? "granted" : "revoked") + " notification listener access");
        } catch (Throwable t) { Log.w(TAG, "setEnabled failed", t); }
    }

    // Keys already mirrored as a card. An update of the same notification is re-shown only if the
    // app did not set FLAG_ONLY_ALERT_ONCE -- the same rule SystemUI uses to decide whether an
    // update heads-up again (progress / streaming updates would otherwise spam the card).
    private final Map<String, Boolean> mShown = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 128; }
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        try {
            if (sbn == null || !apps(this).contains(sbn.getPackageName())) return;
            final Notification n = sbn.getNotification();
            if (n == null) return;
            if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_GROUP_SUMMARY)) != 0) return;
            if (mShown.containsKey(sbn.getKey())
                    && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) return;   // quiet update
            // Silent channels never heads-up, so they get no card either.
            if (rankingMap != null) {
                final Ranking r = new Ranking();
                if (rankingMap.getRanking(sbn.getKey(), r)
                        && r.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) return;
            }
            // Screen off or locked: the overlay would not be visible anyway (TYPE_APPLICATION_OVERLAY
            // sits below keyguard); the normal notification handles those cases.
            final PowerManager pm = getSystemService(PowerManager.class);
            final KeyguardManager km = getSystemService(KeyguardManager.class);
            if ((pm != null && !pm.isInteractive()) || (km != null && km.isKeyguardLocked())) return;
            CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (text == null) text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (text == null) {                                   // InboxStyle: last line
                final CharSequence[] lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null && lines.length > 0) text = lines[lines.length - 1];
            }
            if (title == null && text == null) return;         // nothing worth a big card
            Drawable icon = null;
            try { icon = getPackageManager().getApplicationIcon(sbn.getPackageName()); }
            catch (Throwable ignore) {}
            final CharSequence ft = title, fx = text;
            final Drawable fi = icon;
            final PendingIntent ci = n.contentIntent;
            mShown.put(sbn.getKey(), Boolean.TRUE);            // only once a card is really shown
            mMain.post(() -> showCard(ft, fx, fi, ci));
        } catch (Throwable t) { Log.w(TAG, "onNotificationPosted", t); }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null) mShown.remove(sbn.getKey());
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void showCard(CharSequence title, CharSequence text, Drawable icon, PendingIntent ci) {
        removeCard();
        if (mWm == null) mWm = getSystemService(WindowManager.class);
        final DisplayMetrics dm = getResources().getDisplayMetrics();

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21C1C1E);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(bg);
        card.setElevation(dp(12));

        if (icon != null) {
            final ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            final LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
            ip.rightMargin = dp(14);
            card.addView(iv, ip);
        }
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        if (title != null) {
            final TextView tv = new TextView(this);
            tv.setText(title);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(16);
            tv.setMaxLines(1);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(tv);
        }
        if (text != null) {
            final TextView tx = new TextView(this);
            tx.setText(text);
            tx.setTextColor(0xFFCCCCCC);
            tx.setTextSize(14);
            tx.setMaxLines(3);
            tx.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(tx);
        }
        card.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // tap = open, horizontal swipe = dismiss
        card.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startT; boolean moved;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY(); startT = v.getTranslationX(); moved = false;
                        cancelAuto();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        final float dx = e.getRawX() - downX;
                        if (Math.abs(dx) > dp(8) || Math.abs(e.getRawY() - downY) > dp(8)) moved = true;
                        v.setTranslationX(startT + dx);
                        v.setAlpha(Math.max(0.2f, 1f - Math.abs(startT + dx) / (dm.widthPixels * 0.6f)));
                        return true;
                    case MotionEvent.ACTION_UP:
                        final float total = v.getTranslationX();
                        if (!moved) { fire(ci); removeCard(); return true; }        // tap → open
                        if (Math.abs(total) > dm.widthPixels / 3f) { removeCard(); return true; } // swipe → dismiss
                        v.animate().translationX(0).alpha(1f).setDuration(160).start();           // spring back
                        scheduleAuto();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().translationX(0).alpha(1f).setDuration(160).start();
                        scheduleAuto();
                        return true;
                }
                return false;
            }
        });

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        lp.width = (int) (dm.widthPixels * 0.86f);
        try {
            mWm.addView(card, lp);
            mCard = card;
            card.setAlpha(0f);
            card.animate().alpha(1f).setDuration(180).start();
            scheduleAuto();
        } catch (Throwable t) { Log.w(TAG, "addView failed", t); mCard = null; }
    }

    private void fire(PendingIntent ci) {
        if (ci == null) return;
        try { ci.send(); } catch (Throwable t) { Log.w(TAG, "contentIntent", t); }
    }
    private void scheduleAuto() {
        cancelAuto();
        mAutoDismiss = this::removeCard;
        mMain.postDelayed(mAutoDismiss, AUTO_DISMISS_MS);
    }
    private void cancelAuto() {
        if (mAutoDismiss != null) { mMain.removeCallbacks(mAutoDismiss); mAutoDismiss = null; }
    }
    private void removeCard() {
        cancelAuto();
        if (mCard != null && mWm != null) {
            try { mWm.removeView(mCard); } catch (Throwable ignore) {}
        }
        mCard = null;
    }

    @Override public void onListenerDisconnected() { mMain.post(this::removeCard); }
    @Override public void onDestroy() { mMain.post(this::removeCard); super.onDestroy(); }
}
