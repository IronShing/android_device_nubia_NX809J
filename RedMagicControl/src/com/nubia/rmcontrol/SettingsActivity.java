package com.nubia.rmcontrol;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/**
 * RedMagic Control — dependency-free grouped settings panel. Every control maps
 * to a persist system property applied by init/vendor_init (fan, pump) or the
 * hwcontrol daemon (RGB, edge, triggers, haptics, auto-fan). Changing a hwcontrol
 * setting bumps sys.rm.settings_rev so the daemon re-applies immediately.
 */
public class SettingsActivity extends Activity {

    // effect: Off/Constant/Breathing/Flash/Flow -> aw22xxx effect codes
    private static final String[] FX_NAMES = {"Off", "Constant", "Breathing", "Flash", "Flow"};
    private static final int[]    FX_VALS  = {0, 2, 3, 4, 6};
    // color: 1..9 per the ColorfulLight doc
    private static final String[] COL_NAMES = {"Red", "Orange", "Yellow", "Green", "Cyan", "Light Blue", "Blue", "Purple", "Pink"};
    private static final int[]    COL_ARGB  = {0xFFF44336, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50, 0xFF00BCD4, 0xFF03A9F4, 0xFF2196F3, 0xFF9C27B0, 0xFFE91E63};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        // ---- Cooling ----
        header(root, "Cooling");
        levelRow(root, "Cooling fan", "persist.sys.fan.level",
                new String[]{"Off", "1", "2", "3", "4", "5"}, false);
        rmSwitch(root, "Auto fan (by temperature)", "persist.sys.rm.fan_auto", false);
        levelRow(root, "Liquid cooling pump", "persist.sys.cooling.level",
                new String[]{"Off", "Low", "Medium", "High"}, false);

        // ---- Lighting ---- (position codes: logo=1 shoulder=2 fan=3)
        header(root, "Lighting (RGB) — experimental");
        ledZone(root, "Logo", "persist.sys.rm.led.logo", 1);
        ledZone(root, "Shoulder strip", "persist.sys.rm.led.shoulder", 2);
        ledZone(root, "Fan ring", "persist.sys.rm.led.fan", 3);

        // ---- Shoulder triggers ----
        header(root, "Shoulder triggers");
        rmSwitch(root, "Left trigger (L)", "persist.sys.rm.trigger_left", true);
        rmSwitch(root, "Right trigger (R)", "persist.sys.rm.trigger_right", true);

        // ---- Magic slider (Settings.System, stock key) ----
        header(root, "Magic slider");
        sliderRow(root);

        // ---- Haptics ----
        header(root, "Haptics");
        haptics(root);

        // ---- Touch ----
        header(root, "Touch");
        rmSwitch(root, "Edge touch rejection (anti-grip)", "persist.sys.rm.edge_reject", true);

        // ---- Audio ----
        header(root, "Audio");
        addSwitch(root, "Loudness (V4A makeup gain)", "persist.sys.loudness.enabled");
        loudnessGain(root);

        // ---- Wake ----
        header(root, "Wake");
        addSwitch(root, getString(R.string.tile_fpwake), "persist.sys.fp_wake.enabled");
        addSwitch(root, getString(R.string.tile_dt2w), "persist.sys.dt2w.enabled");

        // ---- Battery ----
        header(root, "Battery");
        dozeOffline(root);
        note(root, "When the phone has no network at all — airplane mode with WiFi off, or no "
                + "signal — Android still wakes it every few minutes to run background work it "
                + "cannot actually do. This makes it stay asleep longer in that situation.\n\n"
                + "It switches itself off the moment any network appears, so notifications are "
                + "never held back. Airplane mode with WiFi ON still receives push, so it stays "
                + "off there too.\n\n"
                + "Alarms, timers and reminders always ring, on or off. Only offline background "
                + "work (local backups, indexing) waits longer. Expect a small saving.");
        minutesRow(root, "Light doze window", OfflineDoze.KEY_LIGHT_IDLE, OfflineDoze.DEF_LIGHT_IDLE);
        minutesRow(root, "Light doze maximum", OfflineDoze.KEY_LIGHT_MAX, OfflineDoze.DEF_LIGHT_MAX);
        minutesRow(root, "Deep doze maintenance", OfflineDoze.KEY_IDLE_PENDING, OfflineDoze.DEF_IDLE_PENDING);
        minutesRow(root, "Deep doze maximum", OfflineDoze.KEY_MAX_PENDING, OfflineDoze.DEF_MAX_PENDING);
        note(root, "How long the phone sleeps between wake-ups while offline. Android's own "
                + "values are 5 / 30 / 5 / 10 min — higher means fewer wake-ups. These only "
                + "apply while offline and only while the switch above is on.");
        batteryStatsRow(root);

        // ---- Interface ----
        header(root, "Interface");
        animationRow(root);
        note(root, "Stock RedMagic runs its transitions faster than AOSP's default, which is a lot "
                + "of why it feels quicker (XDA #339). \"Fast\" matches roughly what stock does. "
                + "This is the same setting as Developer options > animation scales, so if you have "
                + "already changed it there, this will show and overwrite that value.");

        // ---- Desktop ----
        header(root, "Desktop");
        desktopRow(root);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        setTitle(R.string.app_name);
    }

    /**
     * Section-header colour taken from the theme, not hardcoded.
     *
     * The app used a fixed #8AB4F8 while it was pinned to a light-only theme. Now that it follows
     * the system day/night setting, a fixed light-blue would be low-contrast on the light
     * background, so resolve the theme's own accent and fall back to the old value.
     */
    private int accentColor() {
        final android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true) && tv.data != 0) {
            return tv.data;
        }
        return 0xFF8AB4F8;
    }

    // ---------- section header ----------
    private void header(LinearLayout root, String text) {
        TextView h = new TextView(this);
        h.setText(text.toUpperCase());
        h.setTextSize(13);
        h.setTextColor(accentColor());
        h.setPadding(0, dp(20), 0, dp(6));
        root.addView(h);
    }

    // ---------- level spinner row (fan/pump) ----------
    private void levelRow(LinearLayout root, String title, final String key, String[] names, boolean bumpRev) {
        LinearLayout row = labelledRow(root, title);
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        sp.setSelection(clamp(parseInt(Prop.get(key, "0"), 0), 0, names.length - 1));
        sp.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Prop.set(key, Integer.toString(pos));
                if (bumpRev) bumpRev();
            }
        });
        row.addView(sp);
    }

    // ---------- RGB zone: effect + color spinners -> "0x[POS][EEE][CCC]" ----------
    // vendor_init (redmagic_hw_arm.rc) writes this value to the aw22xxx effect node.
    private void ledZone(LinearLayout root, String title, final String key, final int position) {
        TextView t = new TextView(this); t.setTextSize(15); t.setText(title);
        t.setPadding(0, dp(8), 0, 0);
        root.addView(t);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        int fxIdx = 0, colIdx = 0;   // restore spinners from stored 0xP EEE CCC
        String v = Prop.get(key, "").trim();
        if (v.startsWith("0x") && v.length() == 9) {
            fxIdx  = idxOf(FX_VALS, parseInt(v.substring(3, 6), 0));
            colIdx = clamp(parseInt(v.substring(6, 9), 1) - 1, 0, COL_NAMES.length - 1);
        }

        final Spinner fx = new Spinner(this);
        fx.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, FX_NAMES));
        fx.setSelection(fxIdx);
        final Spinner col = new Spinner(this);
        col.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, COL_NAMES));
        col.setSelection(colIdx);

        SimpleSel apply = new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v2, int pos, long id) {
                int fxVal = FX_VALS[fx.getSelectedItemPosition()];
                int colVal = col.getSelectedItemPosition() + 1;
                Prop.set(key, String.format("0x%d%03d%03d", position, fxVal, colVal));
                bumpRev();
            }
        };
        fx.setOnItemSelectedListener(apply);
        col.setOnItemSelectedListener(apply);

        row.addView(fx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);
    }

    // ---------- magic slider (stock Settings.System key) ----------
    /**
     * Magic slider.
     *
     * The old version of this row wrote Settings.System "fourth_physical_key_function_value" — the
     * key stock RedMagicOS uses. That does nothing here: the handler for it (SlideKeysCtrl) lives
     * inside ZTE's own system server, which an AOSP-based ROM does not have, so every option in
     * that dropdown was inert. It is now driven by our own slider_uewake daemon + SliderWatcher.
     *
     * We still force the stock key to 0 ("no system handling"), which is what the hardware
     * reference recommends when something else owns the switch — harmless if nothing reads it.
     */
    private void sliderRow(LinearLayout root) {
        // Tell the (absent) stock handler to keep its hands off.
        try { Settings.System.putInt(getContentResolver(), "fourth_physical_key_function_value", 0); }
        catch (Exception ignored) {}

        // Kept short on purpose: this Spinner shares a horizontal row with its label, so a long
        // item ("Launch an app, home on slide back") forces the Spinner wide and squeezes the
        // "Slider action" text to a sliver. The row now splits 50/50 as well (see below).
        final String[] modeNames = {"Do nothing", "Flashlight", "Launch app", "App + home", "Key code"};
        final int[] modeVals = {SliderWatcher.MODE_NOTHING, SliderWatcher.MODE_TORCH,
                                SliderWatcher.MODE_LAUNCH, SliderWatcher.MODE_LAUNCH_HOME,
                                SliderWatcher.MODE_KEYCODE};

        LinearLayout modeRow = labelledRow(root, "Slider action");
        final Spinner mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modeNames));
        mode.setSelection(clamp(idxOf(modeVals, parseInt(Prop.get(SliderWatcher.PROP_MODE, "0"), 0)),
                                0, modeNames.length - 1));
        modeRow.addView(mode, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // App picker — launchable packages only, sorted by visible label so it is browsable.
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<String> pkgs = new java.util.ArrayList<>();
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.Intent probe = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> ris = pm.queryIntentActivities(probe, 0);
            java.util.Collections.sort(ris, new android.content.pm.ResolveInfo.DisplayNameComparator(pm));
            for (android.content.pm.ResolveInfo ri : ris) {
                String p = ri.activityInfo.packageName;
                if (pkgs.contains(p)) continue;                 // one entry per package
                pkgs.add(p);
                labels.add(String.valueOf(ri.loadLabel(pm)));
            }
        } catch (Throwable t) {
            // Fall back to a bare list rather than losing the whole panel.
        }

        LinearLayout appRow = labelledRow(root, "App to launch");
        final Spinner app = new Spinner(this);
        app.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                labels.isEmpty() ? new String[]{"(no apps found)"} : labels.toArray(new String[0])));
        int cur = pkgs.indexOf(Prop.get(SliderWatcher.PROP_APP, ""));
        if (cur >= 0) app.setSelection(cur);
        // Same 50/50 split as the mode row: app labels can be long and would otherwise crush the
        // "App to launch" text.
        appRow.addView(app, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mode.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Prop.set(SliderWatcher.PROP_MODE, String.valueOf(modeVals[pos]));
            }
        });
        app.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < pkgs.size()) Prop.set(SliderWatcher.PROP_APP, pkgs.get(pos));
            }
        });

        keycodeRow(root, "Key code (slide to game)", SliderWatcher.PROP_KEY_ON, 183);
        keycodeRow(root, "Key code (slide back)", SliderWatcher.PROP_KEY_OFF, 184);

        note(root, "On stock, the slider opens Game Space. That handler is part of RedMagic's own "
                + "system software and does not exist here, so the slider does nothing until you "
                + "give it a job above.\n\n"
                + "It fires once per slide: pick \"Launch an app\" to open something when you slide "
                + "towards the game position, or the third option to also return to the home screen "
                + "when you slide back.\n\n"
                + "\"Key code\" makes each slide send a key press instead, so remapper and automation "
                + "apps (Key Mapper, Tasker) can bind it to anything they like. The slider is a "
                + "switch in hardware and carries no key code of its own, which is why remappers "
                + "cannot see it otherwise. F13-F24 are used because nothing on this phone sends "
                + "them. Set a direction to \"None\" to have only one edge of the slide fire.");
    }

    // ---------- haptics: intensity + test ----------
    private void haptics(LinearLayout root) {
        final String key = "persist.sys.rm.haptic_ms";
        final TextView label = new TextView(this);
        label.setTextSize(13);
        int cur = parseInt(Prop.get(key, "40"), 40);
        label.setText("Test pulse: " + cur + " ms");
        root.addView(label);
        SeekBar bar = new SeekBar(this);
        bar.setMax(200);
        bar.setProgress(clamp(cur, 5, 200));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { label.setText("Test pulse: " + Math.max(5, p) + " ms"); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) { Prop.set(key, Integer.toString(Math.max(5, s.getProgress()))); }
        });
        root.addView(bar);
        Button test = new Button(this);
        test.setText("Test buzz");
        test.setOnClickListener(v -> {
            if (Prop.get(key, "").isEmpty()) Prop.set(key, "40");   // ensure duration set for vendor_init
            Prop.set("sys.rm.haptic_test", Long.toString(System.currentTimeMillis()));
        });
        root.addView(test);
    }

    // ---------- battery-stats wakeup (read by our patched PeriodicJobManager) ----------
    // Settings refreshes its battery chart hourly with setExactAndAllowWhileIdle(RTC_WAKEUP) — an
    // alarm class Doze is NOT allowed to defer, which makes it the largest idle waker on a stock
    // build and immune to every Doze setting above it in this screen.
    private void batteryStatsRow(LinearLayout root) {
        final String key = "persist.sys.rm.batteryjob_hours";
        final String[] names = {"Hourly (stock)", "Every 2 hours", "Every 4 hours",
                                "Every 6 hours", "Every 12 hours", "Off"};
        final String[] vals = {"1", "2", "4", "6", "12", "0"};

        LinearLayout row = labelledRow(root, "Battery stats refresh");
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        sp.setSelection(idxOfValue(vals, Prop.get(key, "1")));
        sp.setOnItemSelectedListener(new SimpleSel() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Prop.set(key, vals[pos]);
            }
        });
        row.addView(sp);

        note(root, "Settings wakes the phone every hour, on the hour, just to refresh the battery "
                + "usage chart. It is exempt from Doze, so the settings above cannot defer it — on "
                + "a night on the desk that is around nine wake-ups on its own.\n\n"
                + "Lowering this is safe; the only effect is that the battery chart updates less "
                + "often. \"Off\" stops the chart accumulating new history.");
    }

    private static int idxOfValue(String[] vals, String v) {
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(v)) return i;
        return 0;
    }

    // ---------- desktop windowing (OverlayManager, not a property) ----------
    private void desktopRow(LinearLayout root) {
        LinearLayout row = labelledRow(root, "Desktop on the phone screen");
        final Switch sw = new Switch(this);
        sw.setChecked(DesktopIntegration.isOnDeviceDesktopEnabled(this));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            private boolean mReverting;
            @Override
            public void onCheckedChanged(android.widget.CompoundButton v, boolean on) {
                if (mReverting) return;
                if (!DesktopIntegration.setOnDeviceDesktopEnabled(SettingsActivity.this, on)) {
                    // Don't leave the switch showing a state we failed to apply.
                    mReverting = true;
                    v.setChecked(!on);
                    mReverting = false;
                }
            }
        });
        row.addView(sw);

        note(root, "Turns on Android's desktop windowing for this phone's own screen — resizable, "
                + "overlapping windows instead of one app at a time.\n\n"
                + "It also brings back the white bar across the top of fullscreen apps. That bar is "
                + "desktop windowing's own window control, so it cannot be hidden on its own — the "
                + "two are one switch, and this ROM ships with both off. Expect the interface to "
                + "reload when you change this.\n\n"
                + "A monitor plugged into USB-C does not need this: external displays get desktop "
                + "windowing either way.");

        if (DesktopIntegration.isInstalled(this, DesktopIntegration.MAGICDESK_PKG)) {
            note(root, "MagicDesk is installed. It needs Shizuku running to do anything — open "
                    + "Shizuku and use Start via root (this ROM has root, so you can skip the "
                    + "wireless-debugging pairing in their instructions). MagicDesk is a separate "
                    + "open-source project; report its bugs to that project, not to this ROM.");
        }
    }

    // ---------- reusable ----------
    private LinearLayout labelledRow(LinearLayout root, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView t = new TextView(this); t.setTextSize(16); t.setText(title);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);
        return row;
    }

    private void rmSwitch(LinearLayout root, String title, final String key, boolean def) {
        LinearLayout row = labelledRow(root, title);
        Switch sw = new Switch(this);
        sw.setChecked(Prop.getBool(key, def));
        sw.setOnCheckedChangeListener((v, on) -> { Prop.set(key, on ? "1" : "0"); bumpRev(); });
        row.addView(sw);
    }

    private void addSwitch(LinearLayout root, String title, final String key) {
        LinearLayout row = labelledRow(root, title);
        Switch sw = new Switch(this);
        sw.setChecked(Prop.getBool(key, false));
        sw.setOnCheckedChangeListener((v, on) -> Prop.set(key, on ? "1" : "0"));
        row.addView(sw);
    }

    private void loudnessGain(LinearLayout root) {
        final String key = "persist.sys.loudness_gain_mb";
        final TextView label = new TextView(this);
        label.setTextSize(13);
        int cur = parseInt(Prop.get(key, "800"), 800);
        label.setText(getString(R.string.loudness_gain) + ": +" + (cur / 100) + " dB");
        root.addView(label);
        SeekBar bar = new SeekBar(this);
        bar.setMax(10);
        bar.setProgress(clamp(cur / 100, 0, 10));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { label.setText(getString(R.string.loudness_gain) + ": +" + p + " dB"); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {
                Prop.set(key, Integer.toString(s.getProgress() * 100));
                if (Prop.getBool("persist.sys.loudness.enabled", false)) {
                    Prop.set("persist.sys.loudness.enabled", "0");
                    Prop.set("persist.sys.loudness.enabled", "1");
                }
            }
        });
        root.addView(bar);
    }

    private void bumpRev() { Prop.set("sys.rm.settings_rev", Long.toString(System.currentTimeMillis())); }

    // ---------- offline doze ----------
    private void dozeOffline(LinearLayout root) {
        LinearLayout row = labelledRow(root, "Sleep harder when offline");
        Switch sw = new Switch(this);
        sw.setChecked(Prop.getBool(OfflineDoze.KEY_ENABLED, OfflineDoze.DEF_ENABLED));
        sw.setOnCheckedChangeListener((v, on) -> {
            Prop.set(OfflineDoze.KEY_ENABLED, on ? "1" : "0");
            OfflineDoze.reapply(this);
        });
        row.addView(sw);
    }

    /** Duration picker in minutes; writes the value (not the index) so the prop reads plainly. */
    private void minutesRow(LinearLayout root, String title, final String key, final int def) {
        final int[] mins = {5, 10, 15, 20, 30, 45, 60, 90, 120};
        String[] names = new String[mins.length];
        for (int i = 0; i < mins.length; i++) names[i] = mins[i] + " min";

        LinearLayout row = labelledRow(root, title);
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        sp.setSelection(idxOf(mins, OfflineDoze.minutes(key, def)));
        sp.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Prop.set(key, Integer.toString(mins[pos]));
                // Takes effect immediately if we are offline right now.
                OfflineDoze.reapply(SettingsActivity.this);
            }
        });
        row.addView(sp);
    }

    /**
     * Key-code picker for the slider (XDA #337).
     *
     * <p>Offers F13-F24 because those are the only codes that are simultaneously unused by this
     * device and mapped by Android's Generic.kl, so they arrive at apps as real Android key codes
     * (KEYCODE_F13 ...) rather than being swallowed by the input stack. "None" leaves that
     * direction unbound, which is what you want when only one edge of the slide should fire.
     *
     * <p>Values written are LINUX input codes -- slider_uewake feeds them straight to uinput.
     */
    private void keycodeRow(LinearLayout root, String title, final String key, int def) {
        final int[] codes = new int[13];
        final String[] names = new String[13];
        codes[0] = 0; names[0] = "None";
        for (int i = 1; i < 13; i++) {           // 183..194 == F13..F24
            codes[i] = 182 + i;
            names[i] = "F" + (12 + i) + "  (" + codes[i] + ")";
        }

        LinearLayout row = labelledRow(root, title);
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        int cur = idxOf(codes, parseInt(Prop.get(key, Integer.toString(def)), def));
        sp.setSelection(clamp(cur, 0, names.length - 1));
        sp.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Prop.set(key, Integer.toString(codes[pos]));
            }
        });
        row.addView(sp);
    }

    /**
     * UI animation speed (XDA #339, NX123Dos).
     *
     * <p>Writes the three platform animation scales together -- window, transition and animator --
     * because changing only one leaves the UI visibly inconsistent (an activity that flies in but
     * whose contents still fade at the old rate). A SMALLER scale means FASTER: 0.75 is about the
     * 1.3x speed-up stock ships, 0 disables animation entirely.
     *
     * <p>These live in Settings.Global, so they need WRITE_SECURE_SETTINGS -- which this app holds
     * and is allowlisted for (see permissions/privapp-permissions-com.nubia.rmcontrol.xml).
     * They persist in /data, so the choice survives a reboot but not a factory reset.
     */
    private void animationRow(LinearLayout root) {
        final float[] scales = {1.0f, 0.75f, 0.5f, 0.0f};
        final String[] names = {"Default (AOSP)", "Fast (like stock)", "Faster", "Off (instant)"};

        LinearLayout row = labelledRow(root, "Animation speed");
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));

        float cur = 1.0f;
        try {
            cur = Settings.Global.getFloat(getContentResolver(),
                    Settings.Global.WINDOW_ANIMATION_SCALE, 1.0f);
        } catch (Exception ignored) {}
        int sel = 0;
        for (int i = 0; i < scales.length; i++) {
            if (Math.abs(scales[i] - cur) < 0.01f) { sel = i; break; }
        }
        sp.setSelection(sel);

        sp.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                final float f = scales[pos];
                try {
                    Settings.Global.putFloat(getContentResolver(),
                            Settings.Global.WINDOW_ANIMATION_SCALE, f);
                    Settings.Global.putFloat(getContentResolver(),
                            Settings.Global.TRANSITION_ANIMATION_SCALE, f);
                    Settings.Global.putFloat(getContentResolver(),
                            Settings.Global.ANIMATOR_DURATION_SCALE, f);
                } catch (Exception e) {
                    // Never let a settings write take the panel down.
                    android.util.Log.w("RMControl", "animation scale write failed", e);
                }
            }
        });
        row.addView(sp);
    }

    /** Small caption under a control. */
    private void note(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setAlpha(0.7f);
        tv.setPadding(0, 0, 0, dp(8));
        root.addView(tv);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private static int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static int idxOf(int[] arr, int val) { for (int i = 0; i < arr.length; i++) if (arr[i] == val) return i; return 0; }

    // Spinner listener that ignores the initial (setSelection) callback noise is unnecessary
    // here since writes are idempotent; base class just stubs onNothingSelected.
    private abstract static class SimpleSel implements AdapterView.OnItemSelectedListener {
        public void onNothingSelected(AdapterView<?> p) {}
    }
}
