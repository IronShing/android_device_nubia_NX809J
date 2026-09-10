/*
 * SPDX-License-Identifier: Apache-2.0
 * Auto-add newly installed apps to the Privacy guard when the feature is on.
 * Registered at runtime by PrivacyGuard.start() (see there for why not the manifest).
 */
package com.nubia.rmcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public class PackageGuardReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) return;
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;   // an update, not new
        final Uri data = intent.getData();
        if (data == null) return;
        final String pkg = data.getSchemeSpecificPart();
        PrivacyGuard.onPackageAdded(ctx, pkg);
    }
}
