package com.nx809j.voiprecorder;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/** Answers to the "Record calls from X?" notification: always / this call only / never. */
public class DecisionReceiver extends BroadcastReceiver {
    private static final String TAG = "VoipRecorder";
    static final String ACTION = "com.nx809j.voiprecorder.DECIDE";
    static final String EXTRA_PKG = "pkg";
    static final String EXTRA_WHAT = "what";
    static final int ALWAYS = 1, ONCE = 2, NEVER = 3;

    @Override public void onReceive(Context ctx, Intent i) {
        String pkg = i.getStringExtra(EXTRA_PKG);
        int what = i.getIntExtra(EXTRA_WHAT, 0);
        if (pkg == null) return;
        ctx.getSystemService(NotificationManager.class).cancel(CallDetector.askNotifId(pkg));
        String label = Util.appLabel(ctx, pkg);
        switch (what) {
            case ALWAYS:
                new Prefs(ctx).setDecision(pkg, true);
                Toast.makeText(ctx, label + " calls will be recorded", Toast.LENGTH_SHORT).show();
                CallDetector.get(ctx).recordNow(pkg);     // including this one, if still up
                break;
            case NEVER:
                new Prefs(ctx).setDecision(pkg, false);
                Toast.makeText(ctx, label + " calls will not be recorded", Toast.LENGTH_SHORT).show();
                break;
            case ONCE:
                if (!CallDetector.get(ctx).recordNow(pkg)) {
                    Toast.makeText(ctx, "The " + label + " call has already ended", Toast.LENGTH_SHORT).show();
                }
                break;
        }
        Log.i(TAG, "decision " + what + " for " + pkg);
    }
}
