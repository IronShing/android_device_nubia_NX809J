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

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        setTitle(R.string.app_name);
    }

    // ---------- section header ----------
    private void header(LinearLayout root, String text) {
        TextView h = new TextView(this);
        h.setText(text.toUpperCase());
        h.setTextSize(13);
        h.setTextColor(0xFF8AB4F8);
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
    private void sliderRow(LinearLayout root) {
        final String KEY = "fourth_physical_key_function_value";
        final String[] names = {"Disabled", "Camera", "Game Space", "Sound controls", "Launch app", "Shortcut"};
        final int[] vals = {0, 1, 2, 3, 16, 17};
        LinearLayout row = labelledRow(root, "Slider action");
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        int curv = Settings.System.getInt(getContentResolver(), KEY, 2);
        sp.setSelection(clamp(idxOf(vals, curv), 0, names.length - 1));
        sp.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                try { Settings.System.putInt(getContentResolver(), KEY, vals[pos]); } catch (Exception e) {}
            }
        });
        row.addView(sp);
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
