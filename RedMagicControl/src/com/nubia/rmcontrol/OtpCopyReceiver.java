/*
 * SPDX-License-Identifier: Apache-2.0
 * Handles the notification "Copy" action: puts the code on the clipboard and dismisses.
 */
package com.nubia.rmcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class OtpCopyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!OtpSms.ACTION_COPY.equals(intent.getAction())) return;
        final String code = intent.getStringExtra(OtpSms.EXTRA_CODE);
        if (code == null) return;
        if (OtpSms.copyToClipboard(ctx, code)) {
            Toast.makeText(ctx, "Code " + code + " copied", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(ctx, "Could not copy the code", Toast.LENGTH_SHORT).show();
        }
        OtpSms.cancel(ctx);
        ctx.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
    }
}
