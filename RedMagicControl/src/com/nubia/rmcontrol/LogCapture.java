/*
 * SPDX-License-Identifier: Apache-2.0
 * Power-menu "Capture log": SystemUI's CaptureLogAction (GlobalActionsDialogLite) broadcasts
 * com.nubia.rmcontrol.CAPTURE_LOG once the power menu has closed; we dump every logcat buffer
 * plus a device header, getprop and the newest /data/anr traces (hung-process section) into
 * Download/RMControl-logs/log_<stamp>.txt (MediaStore, so it shows
 * in Files) and post a "tap to share" notification. No adb, no root: uid system may read logd
 * without READ_LOGS. Secure rm_powermenu_log (default on) hides the menu item (SystemUI reads
 * it) and is the switch in our Settings; "Capture now" there calls capture() directly.
 *
 * Sharing: the framework refuses to let uid SYSTEM issue URI grants ("the system cannot issue a
 * Uri permission grant ... use startActivityAsCaller()"), so a MediaStore URI in ACTION_SEND
 * opens the chooser but the target then cannot read it. The one exemption is Settings' own
 * FileProvider (com.android.settings.files, cache-path "my_cache"); Settings shares our uid, so
 * the share copy is written into Settings' cache dir and shared through that authority.
 *
 * The receiver is registered at runtime (this app is android:persistent, so it is always
 * there) rather than in the manifest: PackageManager only re-parses the manifest at boot, so a
 * live bind-mount swap of the APK would not see a new manifest component. It is exported but
 * guarded by STATUS_BAR_SERVICE (signature|privileged), which SystemUI holds and ordinary apps
 * cannot get, so nobody else can trigger captures.
 */
package com.nubia.rmcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogCapture {
    private static final String TAG = "RMControl.LogCapture";
    static final String ACTION = "com.nubia.rmcontrol.CAPTURE_LOG";
    private static final String CHANNEL = "logcapture";
    private static final int NOTIF_ID = 0x10c;
    private static final String REL_PATH = Environment.DIRECTORY_DOWNLOADS + "/RMControl-logs/";
    private static final int KEEP = 10;          // newest captures kept in Download/RMControl-logs
    static final String SETTING_ENABLED = "rm_powermenu_log";   // Secure; SystemUI reads it too
    private static final String SETTINGS_PKG = "com.android.settings";
    private static final String SHARE_AUTHORITY = "com.android.settings.files";
    private static final String SHARE_SUBDIR = "rmc_logs";
    private static boolean sBusy;

    private LogCapture() {}

    static boolean enabled(Context ctx) {
        return Settings.Secure.getInt(ctx.getContentResolver(), SETTING_ENABLED, 1) != 0;
    }

    static void start(Context ctx) {
        final Context app = ctx.getApplicationContext();
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (!ACTION.equals(intent.getAction()) || !enabled(app)) return;
                final PendingResult pr = goAsync();
                captureAsync(app, pr::finish);
            }
        }, new IntentFilter(ACTION), android.Manifest.permission.STATUS_BAR_SERVICE, null,
                Context.RECEIVER_EXPORTED);
    }

    /** One capture at a time; a request while one runs is dropped. */
    static void captureAsync(Context ctx, Runnable done) {
        synchronized (LogCapture.class) {
            if (sBusy) { if (done != null) done.run(); return; }
            sBusy = true;
        }
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                capture(app);
            } finally {
                synchronized (LogCapture.class) { sBusy = false; }
                if (done != null) done.run();
            }
        }, "logcapture").start();
    }

    /** Settings' cache dir (same uid as us) — the only place uid system can share from. */
    private static File shareDir(Context ctx) throws Exception {
        final File dir = new File(ctx.createPackageContext(SETTINGS_PKG, 0).getCacheDir(), SHARE_SUBDIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        return dir;
    }

    static void capture(Context ctx) {
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Log capture",
                NotificationManager.IMPORTANCE_HIGH));
        nm.notify(NOTIF_ID, new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Capturing log…")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build());

        final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final String name = "log_" + stamp + ".txt";
        final ContentResolver cr = ctx.getContentResolver();
        final Uri table = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri uri = null;
        File shareFile = null;
        long size = 0;
        try {
            final File dir = shareDir(ctx);
            shareFile = new File(dir, name);
            try (OutputStream os = new FileOutputStream(shareFile)) {
                final Writer w = new OutputStreamWriter(os, "UTF-8");
                w.write("# RedMagic Control log capture " + stamp + "\n");
                w.write("# " + Build.FINGERPRINT + "\n");
                w.write("# display=" + Build.DISPLAY + " kernel=" + System.getProperty("os.version")
                        + " uptime=" + (SystemClock.elapsedRealtime() / 1000) + "s\n\n");
                w.write("=== logcat -d -b all -v threadtime ===\n");
                w.flush();
                size += run(os, "logcat", "-d", "-b", "all", "-v", "threadtime");
                w.write("\n=== getprop ===\n");
                w.flush();
                size += run(os, "getprop");
                w.flush();
                size += appendAnrTraces(w);
                w.flush();
            }
            pruneDir(dir);

            // User-visible copy in Download/RMControl-logs (Files app, file managers).
            final ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, REL_PATH);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            uri = cr.insert(table, cv);
            if (uri == null) throw new IllegalStateException("MediaStore insert returned null");
            try (InputStream in = new FileInputStream(shareFile);
                 OutputStream os = cr.openOutputStream(uri)) {
                final byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, cv, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "capture failed", t);
            if (uri != null) {
                try { cr.delete(uri, null, null); } catch (Throwable ignore) {}
            }
            if (shareFile != null) shareFile.delete();
            nm.notify(NOTIF_ID, new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Log capture failed")
                    .setContentText(t.getClass().getSimpleName() + ": " + t.getMessage())
                    .setAutoCancel(true)
                    .build());
            return;
        }
        prune(cr, uri);

        final Uri shareUri = new Uri.Builder().scheme("content").authority(SHARE_AUTHORITY)
                .appendPath("my_cache").appendPath(SHARE_SUBDIR).appendPath(name).build();
        final Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, shareUri)
                .putExtra(Intent.EXTRA_SUBJECT, name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        final Intent chooser = Intent.createChooser(send, "Share log")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent share = PendingIntent.getActivity(ctx, 0, chooser,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final long kb = size / 1024;
        Log.i(TAG, "captured " + uri + " " + REL_PATH + name + " (" + kb + " KB), share " + shareUri);
        nm.notify(NOTIF_ID, new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle("Log captured (" + kb + " KB)")
                .setContentText("Download/RMControl-logs/" + name + " — tap to share")
                .setContentIntent(share)
                .addAction(new Notification.Action.Builder(null, "Share", share).build())
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build());
    }

    private static final int ANR_FILES = 3;
    private static final long ANR_MAX_AGE_MS = 48L * 3600 * 1000;
    private static final int ANR_MAX_CHARS = 400 * 1024;

    /**
     * The ANR dialog ("System UI isn't responding") leaves no stack in logcat — only the CPU
     * table — so the trace file is the one thing that says what the main thread was doing.
     * /data/anr is 0775 system and the traces are owned by system_server (uid system, same as
     * us; system_app has anr_data_file read in sepolicy). Newest few from the last two days,
     * trimmed to the hung process (the first "----- pid N" section; system_server dumps up to
     * ~70 more pids after it) so the log stays shareable.
     */
    private static long appendAnrTraces(Writer w) throws Exception {
        final File[] all = new File("/data/anr").listFiles();
        if (all == null) {
            w.write("\n=== /data/anr: not readable ===\n");
            return 0;
        }
        final List<File> files = new ArrayList<>();
        final long cutoff = System.currentTimeMillis() - ANR_MAX_AGE_MS;
        for (File f : all) if (f.isFile() && f.lastModified() >= cutoff) files.add(f);
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        w.write("\n=== /data/anr (" + all.length + " files, newest " + Math.min(ANR_FILES, files.size())
                + " of the last 48 h, hung process only) ===\n");
        long total = 0;
        for (int i = 0; i < files.size() && i < ANR_FILES; i++) {
            final File f = files.get(i);
            w.write("\n--- " + f.getName() + " (" + f.length() + " bytes, "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(f.lastModified()))
                    + ") ---\n");
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                String line;
                int chars = 0;
                while ((line = r.readLine()) != null) {
                    w.write(line);
                    w.write('\n');
                    chars += line.length() + 1;
                    if (line.startsWith("----- end ")) break;
                    if (chars > ANR_MAX_CHARS) { w.write("[truncated]\n"); break; }
                }
                total += chars;
            } catch (Throwable t) {
                w.write("[unreadable: " + t + "]\n");
            }
        }
        return total;
    }

    /** Runs a command, streams stdout+stderr into os, returns the byte count. */
    private static long run(OutputStream os, String... cmd) throws Exception {
        final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        long total = 0;
        try (InputStream in = p.getInputStream()) {
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); total += n; }
        }
        final int rc = p.waitFor();
        if (rc != 0) Log.w(TAG, Arrays.toString(cmd) + " exit " + rc);
        return total;
    }

    /** Same KEEP policy for the share copies in Settings' cache. */
    private static void pruneDir(File dir) {
        final File[] files = dir.listFiles((d, n) -> n.startsWith("log_") && n.endsWith(".txt"));
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = KEEP; i < files.length; i++) files[i].delete();
    }

    /** Keep the newest KEEP captures of ours (names sort chronologically). */
    private static void prune(ContentResolver cr, Uri keep) {
        try (Cursor c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[] { MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME },
                MediaStore.Downloads.RELATIVE_PATH + "=? AND " + MediaStore.Downloads.DISPLAY_NAME + " LIKE 'log_%.txt'",
                new String[] { REL_PATH }, MediaStore.Downloads.DISPLAY_NAME + " DESC")) {
            if (c == null) return;
            final List<Long> old = new ArrayList<>();
            int i = 0;
            while (c.moveToNext()) {
                if (i++ >= KEEP) old.add(c.getLong(0));
            }
            for (long id : old) {
                cr.delete(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(id)), null, null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "prune failed", t);
        }
    }

    /**
     * "Clear logs" (#318, NX123Dos): wipe the in-memory logcat ring buffers and delete every
     * saved capture, so the next Capture starts clean instead of carrying lines from before a
     * reboot. If persistent on-storage logging is on, its stored files are wiped too (logging
     * keeps running). Runs on a worker thread — logcat -c is quick but the MediaStore sweep isn't.
     */
    static void clearAll(final Context ctx) {
        new Thread(() -> {
            // 1. in-memory ring buffers (uid system may clear logd)
            try {
                final Process p = new ProcessBuilder("logcat", "-b", "all", "-c")
                        .redirectErrorStream(true).start();
                p.getOutputStream().close();
                try (InputStream in = p.getInputStream()) {
                    final byte[] b = new byte[4096];
                    while (in.read(b) > 0) { /* drain */ }
                }
                if (p.waitFor() != 0) Log.w(TAG, "logcat -c nonzero");
            } catch (Exception e) {
                Log.w(TAG, "clear buffers", e);
            }

            // 2. persistent on-storage logs, if any (keeps logging on when it was on)
            try {
                LogPersist.clearStored();
            } catch (Throwable t) {
                Log.w(TAG, "clear persistent", t);
            }

            // 3. saved captures: Settings' share cache + Download/RMControl-logs
            try {
                final File dir = shareDir(ctx);
                final File[] fs = dir.listFiles((d, n) -> n.startsWith("log_") && n.endsWith(".txt"));
                if (fs != null) for (File f : fs) f.delete();
            } catch (Throwable ignore) { /* cache dir may not exist yet */ }
            try {
                final ContentResolver cr = ctx.getContentResolver();
                try (Cursor c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[] { MediaStore.Downloads._ID },
                        MediaStore.Downloads.RELATIVE_PATH + "=? AND "
                                + MediaStore.Downloads.DISPLAY_NAME + " LIKE 'log_%.txt'",
                        new String[] { REL_PATH }, null)) {
                    if (c != null) while (c.moveToNext()) {
                        cr.delete(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                String.valueOf(c.getLong(0))), null, null);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "clear saved", t);
            }
            Log.i(TAG, "logs cleared");
        }, "rmc-logclear").start();
    }
}
