package com.nubia.rmcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** "Allow for 10 min" notification action and the allowance-expiry alarm (see PrivacyGuard). */
public class PrivacyGuardReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final String action = intent.getAction();
        if (PrivacyGuard.ACTION_ALLOW.equals(action)) {
            final String pkg = intent.getStringExtra(PrivacyGuard.EXTRA_PKG);
            if (pkg != null) PrivacyGuard.allowFor(ctx, pkg, PrivacyGuard.ALLOW_MS);
        } else if (PrivacyGuard.ACTION_REAPPLY.equals(action)) {
            PrivacyGuard.apply(ctx);
        }
    }
}
