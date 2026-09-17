/*
 * Foreground Privacy Guard prompt: a dialog drawn over the app that just got blocked, instead of
 * a heads-up the user has to pull down. Started by PrivacyGuard.notifyBlocked only when the
 * blocked package is the one on screen. Offers the timed allow (5 / 10 / 15 min) and a
 * per-screen rule ("always allow on this screen") so e.g. Google Lens can get the camera every
 * time it opens while the rest of the Google app stays blocked.
 *
 * singleInstance: a second permission blocked while the dialog is up (video call = microphone,
 * then camera a few ms later) arrives through onNewIntent and is merged into the same dialog --
 * one "tried to use the microphone and camera" prompt whose Allow grants both.
 */
package com.nubia.rmcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

public class PrivacyPromptActivity extends Activity {

    private AlertDialog mDialog;
    private String mPkg;
    private String mCls;
    private int mBits;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!take(getIntent())) { finish(); return; }
        show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        final String before = mPkg;
        final int bitsBefore = mBits;
        if (!take(intent)) return;
        if (mPkg.equals(before) && mBits == bitsBefore) return;   // same ask again, keep the dialog
        show();
    }

    /** Merge the intent into the prompt state; a different package replaces it. */
    private boolean take(Intent intent) {
        final String pkg = intent.getStringExtra(PrivacyGuard.EXTRA_PKG);
        final int bit = intent.getIntExtra(PrivacyGuard.EXTRA_BIT, 0);
        if (pkg == null || bit == 0) return false;
        if (!pkg.equals(mPkg)) {
            mPkg = pkg;
            mCls = intent.getStringExtra(PrivacyGuard.EXTRA_CLS);
            mBits = 0;
        }
        mBits |= bit;
        PrivacyGuard.sPromptPkg = mPkg;
        PrivacyGuard.sPromptCls = mCls;
        return true;
    }

    private static String glyphs(int bits) {
        final StringBuilder sb = new StringBuilder();
        for (int b = 1; b != 0 && b <= bits; b <<= 1) if ((bits & b) != 0) sb.append(PrivacyGuard.permGlyph(b));
        return sb.toString();
    }

    private static String words(int bits) {
        final StringBuilder sb = new StringBuilder();
        int left = Integer.bitCount(bits);
        for (int b = 1; b != 0 && b <= bits; b <<= 1) {
            if ((bits & b) == 0) continue;
            if (sb.length() > 0) sb.append(--left == 0 ? " and " : ", ");
            else left--;
            sb.append(PrivacyGuard.permWord(b));
        }
        return sb.toString();
    }

    private void show() {
        if (mDialog != null) {
            mDialog.setOnDismissListener(null);
            mDialog.dismiss();
        }
        final String pkg = mPkg, cls = mCls;
        final int bits = mBits;
        final String label = PrivacyGuard.label(this, pkg);
        final String what = words(bits);
        final boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        final int theme = night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;

        final int pad = dp(20);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, dp(8), pad, 0);

        final TextView msg = new TextView(this);
        msg.setText("Blocked by Privacy guard; " + label + " still thinks it has permission. "
                + "Allow just its " + what + " for a few minutes if you need it right now.");
        body.addView(msg);

        final RadioGroup mins = new RadioGroup(this);
        mins.setOrientation(RadioGroup.HORIZONTAL);
        mins.setPadding(0, dp(12), 0, 0);
        int first = -1;
        for (long ms : PrivacyGuard.ALLOW_CHOICES_MS) {
            final RadioButton rb = new RadioButton(this);
            rb.setId((int) (ms / 60000));
            rb.setText((ms / 60000) + " min");
            mins.addView(rb);
            if (first < 0) first = rb.getId();
        }
        mins.check(first);
        body.addView(mins);

        final CheckBox always = new CheckBox(this);
        if (cls != null) {
            always.setText("Always allow on this screen (" + PrivacyGuard.screenLabel(this, pkg, cls)
                    + ") for the chosen time, without asking");
            always.setPadding(0, dp(8), 0, 0);
            body.addView(always);
        }

        mDialog = new AlertDialog.Builder(this, theme)
                .setTitle(glyphs(bits) + " " + label + " tried to use the " + what)
                .setView(body)
                .setPositiveButton("Allow", (d, w) -> {
                    final long ms = mins.getCheckedRadioButtonId() * 60000L;
                    for (int b = 1; b != 0 && b <= bits; b <<= 1) {
                        if ((bits & b) == 0) continue;
                        if (cls != null && always.isChecked()) PrivacyGuard.setAutoAllow(this, pkg, cls, b, ms);
                        PrivacyGuard.allowFor(this, pkg, b, ms);
                    }
                })
                .setNegativeButton("Keep blocked", null)
                .setOnDismissListener(d -> finish())
                .create();
        mDialog.setCanceledOnTouchOutside(true);
        mDialog.show();
    }

    @Override
    protected void onDestroy() {
        if (mPkg != null && mPkg.equals(PrivacyGuard.sPromptPkg)) {
            PrivacyGuard.sPromptPkg = null;
            PrivacyGuard.sPromptCls = null;
        }
        if (mDialog != null) {
            mDialog.setOnDismissListener(null);
            if (mDialog.isShowing()) mDialog.dismiss();
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
