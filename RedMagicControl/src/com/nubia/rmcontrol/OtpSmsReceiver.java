/*
 * SPDX-License-Identifier: Apache-2.0
 * Receives incoming SMS (exported, guarded by BROADCAST_SMS so only the system can invoke it)
 * and hands the body to OtpSms for one-time-code detection.
 */
package com.nubia.rmcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public class OtpSmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        if (!OtpSms.enabled(ctx)) return;
        final SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (msgs == null || msgs.length == 0) return;
        final StringBuilder body = new StringBuilder();
        String sender = null;
        for (SmsMessage m : msgs) {
            if (m == null) continue;
            if (sender == null) sender = m.getOriginatingAddress();
            final String part = m.getMessageBody();
            if (part != null) body.append(part);
        }
        OtpSms.onSms(ctx, body.toString(), sender);
    }
}
