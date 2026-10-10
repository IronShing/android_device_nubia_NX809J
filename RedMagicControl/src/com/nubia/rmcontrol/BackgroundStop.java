package com.nubia.rmcontrol;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * "Stop apps after leaving them": force-stops the apps the user picked once they have been out of
 * the foreground for the chosen time, unless they are playing media -- local playback or a playing
 * (incl. cast / remote) media session. Meant for apps that keep a foreground service running after
 * you leave and give nothing back for it: Stremio's streaming server ran 11.5 h overnight on the
 * daily (2026-10-07 batterystats), keeping the CPU and modem awake.
 *
 * Follows the focused task with its own TaskStackListener (like GamePostProcessing), so it works
 * whether or not Privacy Guard is on. This app is android.uid.system: forceStopPackage and
 * getActiveSessions(null) pass without extra manifest permissions (and without new privapp
 * allowlist entries).
 */
final class BackgroundStop {
    private static final String TAG = "RMControl.BgStop";
    static final String PREFS = "bgstop";
    static final String KEY_PKGS = "pkgs";
    static final String KEY_MIN = "minutes";
    static final int DEFAULT_MIN = 10;

    private static final Handler sH = new Handler(Looper.getMainLooper());
    private static final ArrayMap<String, Runnable> sPending = new ArrayMap<>();
    private static String sTopPkg;

    private BackgroundStop() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> packages(Context c) {
        return new HashSet<>(prefs(c).getStringSet(KEY_PKGS, new HashSet<>()));
    }

    static void setPackages(Context c, Set<String> pkgs) {
        prefs(c).edit().putStringSet(KEY_PKGS, new HashSet<>(pkgs)).apply();
        Log.i(TAG, "apps: " + pkgs);
    }

    static int minutes(Context c) {
        return prefs(c).getInt(KEY_MIN, DEFAULT_MIN);
    }

    static void setMinutes(Context c, int m) {
        prefs(c).edit().putInt(KEY_MIN, m).apply();
    }

    static void start(Context ctx) {
        final Context app = ctx.getApplicationContext();
        try {
            ActivityTaskManager.getService().registerTaskStackListener(new TaskStackListener() {
                @Override public void onTaskStackChanged() { refresh(app); }
                @Override public void onTaskMovedToFront(ActivityManager.RunningTaskInfo ti) {
                    refresh(app);
                }
            });
            refresh(app);
        } catch (Throwable t) {
            Log.e(TAG, "cannot watch the foreground task; background stop is inert", t);
        }
    }

    private static void refresh(Context ctx) {
        sH.post(() -> {
            String top = null;
            try {
                final ActivityTaskManager.RootTaskInfo ti =
                        ActivityTaskManager.getService().getFocusedRootTaskInfo();
                final ComponentName cn = ti == null ? null
                        : (ti.topActivity != null ? ti.topActivity : ti.baseActivity);
                if (cn != null) top = cn.getPackageName();
            } catch (Throwable ignored) { }
            if (top == null || top.equals(sTopPkg)) return;
            final String left = sTopPkg;
            sTopPkg = top;
            cancel(top);                                   // back in front: keep it running
            if (left != null && packages(ctx).contains(left)) schedule(ctx, left);
        });
    }

    private static void cancel(String pkg) {
        final Runnable r = sPending.remove(pkg);
        if (r != null) {
            sH.removeCallbacks(r);
            Log.i(TAG, "back in front, keeping " + pkg);
        }
    }

    private static void schedule(Context ctx, String pkg) {
        final Runnable old = sPending.remove(pkg);
        if (old != null) sH.removeCallbacks(old);
        final Runnable r = () -> check(ctx, pkg);
        sPending.put(pkg, r);
        sH.postDelayed(r, minutes(ctx) * 60_000L);
        Log.i(TAG, "left " + pkg + ": stop in " + minutes(ctx) + " min unless it plays media");
    }

    private static void check(Context ctx, String pkg) {
        sPending.remove(pkg);
        if (pkg.equals(sTopPkg) || !packages(ctx).contains(pkg)) return;
        if (isPlaying(ctx, pkg)) {
            Log.i(TAG, pkg + " is playing media; checking again later");
            schedule(ctx, pkg);
            return;
        }
        try {
            ctx.getSystemService(ActivityManager.class).forceStopPackage(pkg);
            Log.i(TAG, "force-stopped " + pkg + " after " + minutes(ctx)
                    + " min in the background");
        } catch (Throwable t) {
            Log.w(TAG, "force-stop " + pkg + " failed", t);
        }
    }

    /** A playing/buffering media session (covers casting) or an active local audio track. */
    static boolean isPlaying(Context ctx, String pkg) {
        try {
            final MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
            for (MediaController mc : msm.getActiveSessions(null)) {
                if (!pkg.equals(mc.getPackageName())) continue;
                final PlaybackState st = mc.getPlaybackState();
                if (st != null && (st.getState() == PlaybackState.STATE_PLAYING
                        || st.getState() == PlaybackState.STATE_BUFFERING)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "media sessions unavailable", t);
        }
        try {
            final int uid = ctx.getPackageManager().getPackageUid(pkg, 0);
            final AudioManager am = ctx.getSystemService(AudioManager.class);
            for (AudioPlaybackConfiguration c : am.getActivePlaybackConfigurations()) {
                if (c.getClientUid() == uid && c.isActive()) return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "playback configs unavailable", t);
        }
        return false;
    }
}
