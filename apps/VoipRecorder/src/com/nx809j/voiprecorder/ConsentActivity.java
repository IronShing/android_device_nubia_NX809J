package com.nx809j.voiprecorder;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** First-run consent + master on/off. Programmatic UI (no layout resources). */
public class ConsentActivity extends Activity {
    private Prefs prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        requestNeededPerms();

        int pad = dp(20);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        // Let the container hold focus so the Switch below doesn't grab it and make
        // the ScrollView jump down (which hid the top of the consent text).
        root.setFocusableInTouchMode(true);
        sv.addView(root);

        TextView body = new TextView(this);
        body.setText(getString(R.string.consent_body));
        body.setTextSize(15);
        body.setLineSpacing(dp(4), 1f);
        root.addView(body);

        final Switch master = new Switch(this);
        master.setText(R.string.enable_label);
        master.setTextSize(17);
        master.setPadding(0, dp(24), 0, dp(8));
        master.setChecked(prefs.isEnabled() && prefs.hasConsent());
        root.addView(master);

        final Switch allVoip = new Switch(this);
        allVoip.setText(R.string.all_voip_label);
        allVoip.setTextSize(15);
        allVoip.setPadding(0, dp(4), 0, dp(8));
        allVoip.setChecked(prefs.recordAllVoip());
        root.addView(allVoip);

        TextView status = new TextView(this);
        status.setPadding(0, dp(16), 0, dp(16));
        root.addView(status);
        updateStatus(status, master.isChecked());

        final Switch hide = new Switch(this);
        hide.setText(R.string.hide_notif_label);
        hide.setTextSize(15);
        hide.setPadding(0, dp(4), 0, dp(8));
        hide.setChecked(prefs.hideNotification());
        root.addView(hide);

        master.setOnCheckedChangeListener((v, on) -> {
            prefs.setConsent(on);
            prefs.setEnabled(on);
            applyDetection();
            if (on) Toast.makeText(this, R.string.enabled_toast, Toast.LENGTH_SHORT).show();
            updateStatus(status, on);
        });
        allVoip.setOnCheckedChangeListener((v, on) -> prefs.setRecordAllVoip(on));
        hide.setOnCheckedChangeListener((v, on) -> {
            prefs.setHideNotification(on);
            applyDetection();
        });

        Button open = new Button(this);
        open.setText(R.string.recordings_title);
        open.setOnClickListener(v ->
                startActivity(new Intent(this, RecordingsActivity.class)));
        root.addView(open);

        // Self-test: exercise the capture pipeline for 15s without needing a live call.
        Button test = new Button(this);
        test.setText(R.string.selftest_label);
        test.setOnClickListener(v -> runSelfTest());
        root.addView(test);

        // Bring detection into line with the current settings when the app is opened.
        applyDetection();
        setContentView(sv);
        root.requestFocus();
        sv.post(() -> sv.scrollTo(0, 0));   // always open at the top
    }

    /** Start/stop the right detector for the current mode. */
    private void applyDetection() {
        if (prefs.isEnabled() && prefs.hasConsent()) {
            if (prefs.hideNotification()) {
                // no persistent service; the NotificationListener does detection
                stopService(new Intent(this, CallMonitorService.class));
                if (!hasNotificationAccess()) promptNotificationAccess();
            } else {
                startForegroundService(new Intent(this, CallMonitorService.class));
            }
        } else {
            stopService(new Intent(this, CallMonitorService.class));
        }
    }

    private boolean hasNotificationAccess() {
        String flat = android.provider.Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private void promptNotificationAccess() {
        Toast.makeText(this, R.string.grant_notif_access, Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(
                    android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception ignored) {}
    }

    private void runSelfTest() {
        final CallRecorder r = new CallRecorder(this);
        if (!r.start("selftest")) {
            Toast.makeText(this, R.string.selftest_fail, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.selftest_running, Toast.LENGTH_SHORT).show();
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            r.stop();
            Toast.makeText(this, getString(R.string.selftest_saved) + "\n" + r.getOutputFile(),
                    Toast.LENGTH_LONG).show();
        }, 15000);
    }

    private void updateStatus(TextView t, boolean on) {
        if (on) {
            t.setText(R.string.status_on);
            t.setTextColor(Color.parseColor("#2e7d32"));
        } else {
            t.setText(R.string.status_off);
            t.setTextColor(Color.GRAY);
        }
    }

    /** Ask for the runtime permissions the recorder needs (mic + notifications). */
    private void requestNeededPerms() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.RECORD_AUDIO);
        }
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 1);
        }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
