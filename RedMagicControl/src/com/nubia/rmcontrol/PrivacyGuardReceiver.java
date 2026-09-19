package com.nubia.rmcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** "Allow N min" / "Always block" notification actions and the allowance-expiry alarm (see PrivacyGuard). */
public class PrivacyGuardReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final String action = intent.getAction();
        if (PrivacyGuard.ACTION_ALLOW.equals(action)) {
            final String pkg = intent.getStringExtra(PrivacyGuard.EXTRA_PKG);
            final int bit = intent.getIntExtra(PrivacyGuard.EXTRA_BIT, 0);
            final long ms = intent.getLongExtra(PrivacyGuard.EXTRA_MS, PrivacyGuard.ALLOW_MS);
            if (pkg != null && bit != 0) PrivacyGuard.allowFor(ctx, pkg, bit, ms);
        } else if (PrivacyGuard.ACTION_EXTEND.equals(action)) {
            final String pkg = intent.getStringExtra(PrivacyGuard.EXTRA_PKG);
            final int bit = intent.getIntExtra(PrivacyGuard.EXTRA_BIT, 0);
            final long ms = intent.getLongExtra(PrivacyGuard.EXTRA_MS, PrivacyGuard.ALLOW_MS);
            if (pkg != null && bit != 0) PrivacyGuard.allowExtend(ctx, pkg, bit, ms);
        } else if (PrivacyGuard.ACTION_ALLOW_EXPIRED.equals(action)) {
            final String pkg = intent.getStringExtra(PrivacyGuard.EXTRA_PKG);
            final int bit = intent.getIntExtra(PrivacyGuard.EXTRA_BIT, 0);
            if (pkg != null && bit != 0) PrivacyGuard.onAllowExpired(ctx, pkg, bit);
        } else if (PrivacyGuard.ACTION_MUTE.equals(action)) {
            final String pkg = intent.getStringExtra(PrivacyGuard.EXTRA_PKG);
            if (pkg != null) PrivacyGuard.setMuted(ctx, pkg, true);
        } else if (PrivacyGuard.ACTION_END_CALL_ALLOW.equals(action)) {
            final String pkg = intent.getStringExtra(PrivacyGuard.EXTRA_PKG);
            if (pkg != null) PrivacyGuard.endCallAllows(ctx, pkg);
        } else if (PrivacyGuard.ACTION_REAPPLY.equals(action)) {
            PrivacyGuard.apply(ctx);
        }
    }
}
