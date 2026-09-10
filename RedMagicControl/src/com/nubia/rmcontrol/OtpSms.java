/*
 * SPDX-License-Identifier: Apache-2.0
 * RedMagic Control: detect one-time codes in incoming SMS and offer a one-tap copy
 * (and, optionally, auto-copy to the clipboard). No Google SMS-OTP autofill exists on this
 * build; this is the universal "copy the code" path, like iOS / Google Messages.
 */
package com.nubia.rmcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OtpSms {
    private static final String TAG = "RMControl.Otp";
    static final String SETTING_ENABLED  = "rm_otp_enabled";   // master; default ON
    static final String SETTING_AUTOCOPY = "rm_otp_autocopy";  // default OFF
    // Our own "Copy <code>" heads-up. Default OFF: the Messages notification already carries a
    // system-generated "Copy code" smart action, so ours would just be a second prompt.
    static final String SETTING_NOTIFY   = "rm_otp_notify";
    static final String ACTION_COPY = "com.nubia.rmcontrol.OTP_COPY";
    static final String EXTRA_CODE  = "code";
    private static final String CHANNEL = "otp_copy";
    private static final int NOTIF_ID = 0x0791;

    // A verification-code SMS must mention one of these near a short digit run.
    private static final Pattern KEYWORD = Pattern.compile(
            "otp|code|código|verif|passcode|pass\\s?code|one[\\- ]?time|\\bpin\\b|"
            + "password|token|security|auth|رمز|كود|التحقق",
            Pattern.CASE_INSENSITIVE);
    // 4-8 digit run, optionally split once by a space or hyphen (123-456).
    private static final Pattern DIGITS = Pattern.compile("(?<![0-9])([0-9]{3,4}[\\- ]?[0-9]{0,4}|[0-9]{4,8})(?![0-9])");

    private OtpSms() {}

    static boolean enabled(Context c) {
        return Settings.Secure.getInt(c.getContentResolver(), SETTING_ENABLED, 1) != 0;
    }
    static boolean autoCopy(Context c) {
        return Settings.Secure.getInt(c.getContentResolver(), SETTING_AUTOCOPY, 0) != 0;
    }
    static boolean notify(Context c) {
        return Settings.Secure.getInt(c.getContentResolver(), SETTING_NOTIFY, 0) != 0;
    }

    /** Pull a plausible OTP out of an SMS body, or null. Keyword-gated to avoid false hits. */
    static String extractCode(String body) {
        if (body == null || body.isEmpty()) return null;
        if (!KEYWORD.matcher(body).find()) return null;
        Matcher m = DIGITS.matcher(body);
        String best = null;
        while (m.find()) {
            String raw = m.group(1);
            String digits = raw.replaceAll("[^0-9]", "");
            if (digits.length() < 4 || digits.length() > 8) continue;
            // Prefer the first 4-8 digit code; skip year-like standalone "20xx" only if 4 digits
            // and looks like a year and there is a better candidate later.
            if (best == null) best = digits;
        }
        return best;
    }

    static void onSms(Context ctx, String body, String sender) {
        if (!enabled(ctx)) return;
        final String code = extractCode(body);
        if (code == null) return;
        Log.i(TAG, "otp code detected (" + code.length() + " digits) from " + sender);
        if (autoCopy(ctx)) {
            if (copyToClipboard(ctx, code)) {
                Toast.makeText(ctx, "Code " + code + " copied", Toast.LENGTH_SHORT).show();
            }
        }
        if (notify(ctx)) notifyCode(ctx, code, sender);
    }

    static boolean copyToClipboard(Context ctx, String code) {
        try {
            ClipboardManager cm = ctx.getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("Verification code", code));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "clipboard write failed", t);
            return false;
        }
    }

    private static void notifyCode(Context ctx, String code, String sender) {
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Verification codes",
                NotificationManager.IMPORTANCE_HIGH));
        final PendingIntent copy = PendingIntent.getBroadcast(ctx, 1,
                new Intent(ACTION_COPY).setClass(ctx, OtpCopyReceiver.class)
                        .putExtra(EXTRA_CODE, code),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final String from = (sender == null || sender.isEmpty()) ? "" : " from " + sender;
        final Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Copy code: " + code)
                .setContentText("Verification code" + from + " — tap to copy")
                .setContentIntent(copy)
                .addAction(new Notification.Action.Builder(null, "Copy " + code, copy).build())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
        nm.notify(NOTIF_ID, n);
    }

    static void cancel(Context ctx) {
        ctx.getSystemService(NotificationManager.class).cancel(NOTIF_ID);
    }

    /** Take the sensitive runtime perms ourselves; nobody prompts a persistent system app. */
    static void start(Context ctx) {
        for (String perm : new String[] { android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.POST_NOTIFICATIONS }) {
            if (ctx.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                try {
                    ctx.getPackageManager().grantRuntimePermission(ctx.getPackageName(),
                            perm, android.os.Process.myUserHandle());
                } catch (Throwable t) {
                    Log.w(TAG, "self-grant " + perm + " failed", t);
                }
            }
        }
    }
}
