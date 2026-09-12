package com.nubia.rmcontrol;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.ServiceManager;
import android.security.rkp.IGetKeyCallback;
import android.security.rkp.IGetRegistrationCallback;
import android.security.rkp.IRegistration;
import android.security.rkp.IRemoteProvisioning;
import android.security.rkp.RemotelyProvisionedKey;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;
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
    // LED tables. Source of truth: austineyoung2000/Redmagic-Control-Center (working
    // third-party RM control app), cross-checked against which aw_*.bin ship and verified
    // on this device (colour 5 was commanded and rendered green).
    //
    // Value written to the effect node is 0x[ZONE]00[MODE]00[COLOR]; cfg=1 commits.
    // Zones: 1=Logo, 2=Shoulder, 3=Fan ring.
    //
    // Colour index 2 DOES NOT EXIST -- there is no *_2.bin for any zone and the reference
    // app skips it. (The r/RedMagic guide lists 2=Orange; that is wrong.)
    //
    // 101-108 are the multi-colour "RGB" presets. They ship for the FAN ring only
    // (aw_fan{2,3,4,6,a}_10{1..8}.bin), which is why they are appended for that zone alone.
    private static final String[] FX_NAMES = {"Off", "Constant", "Breathing", "Flash"};
    private static final int[]    FX_VALS  = {0, 2, 3, 4};

    private static final String[] COL_NAMES = {"Red", "Orange", "Yellow", "Green",
                                               "Cyan", "Blue", "Purple", "Pink"};
    private static final int[]    COL_VALS  = {1, 3, 4, 5, 6, 7, 8, 9};
    // Fan ring only: the RGB / multi-colour presets.
    private static final String[] RGB_NAMES = {"RGB 1", "RGB 2", "RGB 3", "RGB 4",
                                               "RGB 5", "RGB 6", "RGB 7", "RGB 8"};
    private static final int[]    RGB_VALS  = {101, 102, 103, 104, 105, 106, 107, 108};
    private static final int[]    COL_ARGB  = {0xFFF44336, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50, 0xFF00BCD4, 0xFF03A9F4, 0xFF2196F3, 0xFF9C27B0, 0xFFE91E63};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(16);

        // ---------------------------------------------------------------
        // Tabbed layout. Each section-builder below takes its parent layout,
        // so we just hand it the page for its tab instead of one long scroll.
        // Framework views only (no androidx): a horizontal tab strip on top and
        // one ScrollView per tab in a FrameLayout, one visible at a time.
        // ---------------------------------------------------------------
        LinearLayout cooling  = newPage(pad);
        LinearLayout lighting = newPage(pad);
        LinearLayout controls = newPage(pad);
        LinearLayout audio    = newPage(pad);
        LinearLayout display  = newPage(pad);
        LinearLayout privacy  = newPage(pad);
        LinearLayout system   = newPage(pad);

        // ---- Cooling ----
        header(cooling, "Cooling");
        fanLevelRow(cooling, "Cooling fan");
        levelRow(cooling, "Liquid cooling pump", "persist.sys.cooling.level",
                new String[]{"Off", "Low", "Medium", "High"}, false);
        chargeCoolSwitch(cooling, "Cool while fast charging");
        chargeFanLevelRow(cooling, "Fan speed while charging");
        note(cooling, "Fast charging is the one time this phone gets hot with nobody holding it, so "
                + "charging turns the cooling on by itself: the liquid pump runs, and the fan runs "
                + "at whatever speed you pick here. On \"Auto\" the fan follows the same temperature "
                + "curve as the Cooling fan setting instead of sitting at one fixed speed.");
        rmSwitch(cooling, "Fan ring RGB while auto fan runs", "persist.sys.rm.fan_rgb_follow", true);
        note(cooling, "Lights the fan ring whenever the automatic fan is actually spinning, so the "
                + "ring tells you the phone is cooling itself. It uses the fan ring's own colour "
                + "and effect from the Lighting tab; if that zone's effect is off it keeps your "
                + "colour and just makes it steady, so pick the colour there. Your Lighting setting is restored the moment the fan stops, and this does "
                + "nothing unless the Cooling fan is set to Auto.");
        rmSwitch(cooling, "Cool while gaming", "persist.sys.rm.gamecool", true);
        gameFanLevelRow(cooling, "Fan speed while gaming");
        // Asked repeatedly on XDA: users set this to Off, hear the fan anyway, and report it as a
        // bug. It only governs the gaming boost -- the main "Cooling fan" above is a separate
        // control and ships on Auto.
        note(cooling, "This only controls the extra cooling while you are gaming. It does not turn "
                + "the fan off: \"Cooling fan\" at the top of this tab is the main control and is "
                + "set to Auto out of the box, so the phone still cools itself by temperature. Set "
                + "that one to Off if you want the fan silent.");
        gpuProfileRow(cooling);
        gppSection(cooling);
        // GamePerfWifi has no setting of its own -- it is automatic and therefore invisible, and
        // people have gone looking for a "wifi low latency" toggle that does not exist. Say so.
        note(cooling, "Launching a game from Game Space also maxes out WiFi performance "
                + "automatically \u2014 the WiFi radio is held out of power-saving for as long as the "
                + "game is in the foreground, and released when you leave it. There is no setting "
                + "for this and nothing to turn on; it applies to games you have added to Game "
                + "Space with Performance mode enabled.");

        // ---- Lighting ---- (position codes: logo=1 shoulder=2 fan=3)
        header(lighting, "Lighting (RGB) — experimental");
        ledZone(lighting, "Logo", "persist.sys.rm.led.logo", 1);
        ledZone(lighting, "Shoulder strip", "persist.sys.rm.led.shoulder", 2);
        ledZone(lighting, "Fan ring", "persist.sys.rm.led.fan", 3);
        // Sleeping: nothing may glow, the auto-fan ring included.
        {
            LinearLayout row = labelledRow(lighting, "All lights off during Do Not Disturb");
            Switch sw = new Switch(this);
            sw.setChecked(Prop.getBool(QuietLights.PROP_ENABLED, true));
            sw.setOnCheckedChangeListener((v, on) -> {
                Prop.set(QuietLights.PROP_ENABLED, on ? "1" : "0");
                QuietLights.sync(this);   // re-derive lights_quiet now, not at the next DND edge
                bumpRev();
            });
            row.addView(sw);
        }
        note(lighting, "While Do Not Disturb is on (manual, scheduled or Bedtime mode) the logo, "
                + "shoulder strip and fan ring stay dark even when the fan spins up; your colours "
                + "come back when it ends.");

        // ---- Shoulder triggers ----
        header(controls, "Shoulder triggers");
        rmSwitch(controls, "Left trigger (L)", "persist.sys.rm.trigger_left", true);
        rmSwitch(controls, "Right trigger (R)", "persist.sys.rm.trigger_right", true);

        // Map the shoulder triggers to on-screen taps (trigger_map daemon). When off,
        // the triggers only emit F7/F8 as before; when on, each fires a real touch at
        // the position set below. The per-side switches above still gate each trigger.
        rmSwitch(controls, "Map triggers to touch (games)", "persist.sys.rm.trig_map", false);
        LinearLayout trigRow = labelledRow(controls, "Position the L / R touch targets");
        Button trigPos = new Button(this);
        trigPos.setText("Position");
        trigPos.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, TriggerMapperActivity.class)));
        trigRow.addView(trigPos);
        note(controls, "With mapping on, pressing a shoulder trigger taps the screen where you "
                + "placed its L/R marker \u2014 e.g. the fire button in a shooter. The tap is injected "
                + "by the system on its own touch pointer, so it works alongside your thumbs with no "
                + "lag. Left = F8, right = F7.");

        // ---- Magic slider (Settings.System, stock key) ----
        header(controls, "Magic slider");
        sliderRow(controls);

        // ---- Haptics ----
        header(controls, "Haptics");
        haptics(controls);

        // ---- Touch ----
        header(controls, "Touch");
        rmSwitch(controls, "Edge touch rejection (anti-grip)", "persist.sys.rm.edge_reject", true);

        // ---- Audio ----
        header(audio, "Audio");
        // On by default: the stock ROM is audibly louder than AOSP and this makeup gain is what
        // closes that gap, so shipping it off makes the ROM sound broken out of the box. The
        // shipped default is also set in product.prop so the daemon applies it before the user
        // ever opens this panel; the default here only has to agree with it.
        // NOT ViPER4Android. This drives our own AOSP LoudnessEnhancer daemon
        // (/system_ext/bin/loudness). The old label "Loudness (V4A makeup gain)" implied it
        // configured V4A and caused real confusion -- a user with V4A switched off saw this on and
        // reasonably expected V4A to be doing something. They are complementary: V4A shapes the
        // sound, this adds raw gain on top.
        addSwitch(audio, "Extra loudness (makeup gain)", "persist.sys.loudness.enabled", true);
        loudnessGain(audio);
        note(audio, "Adds raw volume on top of whatever else is running, with a built-in limiter. "
                + "This is NOT ViPER4Android \u2014 V4A is a separate app with its own settings, and "
                + "the two work together: V4A shapes the sound, this makes it louder.");

        // ---- Display: external display refresh ----
        header(display, "External display");
        displayRefreshRow(display);
        note(display, "For an external screen over USB-C (dock or adapter). If the picture drops "
                + "out, glitches or never appears, the cable or hub probably cannot carry the "
                + "bandwidth the screen is asking for. Set a limit here and every screen you "
                + "connect is held within it: applied automatically once the connection has "
                + "settled, or immediately with Apply now.\n\n"
                + "The lists start with safe values and grow with whatever your screen advertises "
                + "once one has been scanned.");

        // ---- Interface ----
        header(display, "Interface");
        animationRow(display);
        note(display, "Stock RedMagic runs its transitions faster than AOSP's default, which is a lot "
                + "of why it feels quicker (XDA #339). \"Fast\" matches roughly what stock does. "
                + "This is the same setting as Developer options > animation scales, so if you have "
                + "already changed it there, this will show and overwrite that value.");

        // ---- Desktop ----
        header(display, "Desktop");
        desktopRow(display);

        // ---- Wake ----
        header(system, "Wake");
        addSwitch(system, getString(R.string.tile_fpwake), "persist.sys.fp_wake.enabled");
        addSwitch(system, getString(R.string.tile_dt2w), "persist.sys.dt2w.enabled");

        // ---- Privacy: per-app camera / mic / location guard ----
        header(privacy, "Privacy guard");
        privacySection(privacy);

        // ---- Privacy: per-app "ignore FLAG_SECURE" (framework reads Secure rm_screenshot_allow) ----
        header(privacy, "Screenshots");
        screenshotSection(privacy);

        // ---- Privacy: VoIP call recording (engine = VoipRecorder priv-app, switches here) ----
        header(privacy, "VoIP call recording");
        voipRecorderSection(privacy);

        header(privacy, "SMS verification codes");
        otpSmsSection(privacy);

        // ---- Attestation ----
        header(system, "Attestation");
        rkpRow(system);

        // ---- Battery ----
        header(system, "Battery");
        dozeOffline(system);
        note(system, "When the phone has no network at all — airplane mode with WiFi off, or no "
                + "signal — Android still wakes it every few minutes to run background work it "
                + "cannot actually do. This makes it stay asleep longer in that situation.\n\n"
                + "It switches itself off the moment any network appears, so notifications are "
                + "never held back. Airplane mode with WiFi ON still receives push, so it stays "
                + "off there too.\n\n"
                + "Alarms, timers and reminders always ring, on or off. Only offline background "
                + "work (local backups, indexing) waits longer. Expect a small saving.");
        minutesRow(system, "Light doze window", OfflineDoze.KEY_LIGHT_IDLE, OfflineDoze.DEF_LIGHT_IDLE);
        minutesRow(system, "Light doze maximum", OfflineDoze.KEY_LIGHT_MAX, OfflineDoze.DEF_LIGHT_MAX);
        minutesRow(system, "Deep doze maintenance", OfflineDoze.KEY_IDLE_PENDING, OfflineDoze.DEF_IDLE_PENDING);
        minutesRow(system, "Deep doze maximum", OfflineDoze.KEY_MAX_PENDING, OfflineDoze.DEF_MAX_PENDING);
        note(system, "How long the phone sleeps between wake-ups while offline. Android's own "
                + "values are 5 / 30 / 5 / 10 min — higher means fewer wake-ups. These only "
                + "apply while offline and only while the switch above is on.");
        batteryStatsRow(system);

        setContentView(buildTabbedRoot(
                new String[]{"Cooling", "Lighting", "Controls", "Audio", "Display", "Privacy", "System"},
                new LinearLayout[]{cooling, lighting, controls, audio, display, privacy, system}));
        setTitle(R.string.app_name);
    }

    private int sysBarPx(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }
    private int statusBarPx() { int h = sysBarPx("status_bar_height"); return h > 0 ? h : dp(24); }
    private int navBarPx() { return sysBarPx("navigation_bar_height"); }

    /** One tab page: a vertical column, padded, ready for section builders. */
    private LinearLayout newPage(int pad) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(pad, pad, pad, pad);
        return p;
    }

    /**
     * Framework-only tab container (no androidx): a horizontally scrollable tab
     * strip on top and one ScrollView per page in a FrameLayout, one visible at
     * a time. Selected tab is accented + underlined.
     */
    private View buildTabbedRoot(final String[] titles, final LinearLayout[] pages) {
        final int n = pages.length;
        final int accent = accentColor();
        final int dim = 0x99888888;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // Keep the tab strip out from under the status bar and the last rows out from
        // under the nav bar. Prefer live insets (correct with cutouts/gesture nav);
        // fall back to the framework dimens. Without this the tab strip rendered under
        // the status bar and its taps were swallowed by it.
        col.setPadding(0, statusBarPx(), 0, navBarPx());
        col.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets sb =
                    insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
            return insets;
        });

        final FrameLayout host = new FrameLayout(this);
        final ScrollView[] scrolls = new ScrollView[n];
        for (int i = 0; i < n; i++) {
            ScrollView sv = new ScrollView(this);
            sv.addView(pages[i]);
            sv.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            scrolls[i] = sv;
            host.addView(sv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        // Equal-width tab strip: all tabs always visible (no horizontal scroll), so
        // none can hide off-screen.
        final LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);

        final TextView[] tv = new TextView[n];
        final View[] under = new View[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(2), dp(12), dp(2), 0);

            TextView t = new TextView(this);
            t.setText(titles[i]);
            t.setAllCaps(true);
            t.setMaxLines(1);
            t.setGravity(Gravity.CENTER);
            // Seven equal-width cells on a 1216px panel leave ~57dp each, which is not enough for
            // "LIGHTING"/"CONTROLS" at a fixed 12sp: the label was clipped mid-word ("LIGHTIN",
            // "CONTRO"). Autosize shrinks only the labels that need it, so the short ones keep
            // the full size. setTextSize() is ignored once autosizing is on, hence it is gone.
            t.setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP);
            // MATCH_PARENT, not the default WRAP_CONTENT: autosizing shrinks text to fit the
            // view's own width, and a WRAP_CONTENT label is measured at its desired width, so it
            // would never shrink and the clipping would survive the fix.
            t.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            t.setTextColor(i == 0 ? accent : dim);
            t.setTypeface(null, i == 0 ? android.graphics.Typeface.BOLD
                                       : android.graphics.Typeface.NORMAL);
            tv[i] = t;
            cell.addView(t);

            View u = new View(this);
            LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
            ulp.topMargin = dp(10);
            u.setLayoutParams(ulp);
            u.setBackgroundColor(i == 0 ? accent : 0x00000000);
            under[i] = u;
            cell.addView(u);

            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    for (int k = 0; k < n; k++) {
                        scrolls[k].setVisibility(k == idx ? View.VISIBLE : View.GONE);
                        tv[k].setTextColor(k == idx ? accent : dim);
                        tv[k].setTypeface(null, k == idx ? android.graphics.Typeface.BOLD
                                                         : android.graphics.Typeface.NORMAL);
                        under[k].setBackgroundColor(k == idx ? accent : 0x00000000);
                    }
                }
            });
            tabs.addView(cell, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(0x22888888);

        col.addView(tabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        col.addView(divider);
        col.addView(host, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    // ---------- external display: persistent caps ----------
    /**
     * Two caps ("no more than 1080p", "no more than 60 Hz") re-applied automatically on every
     * connection by {@link ExternalDisplay}. This replaced a per-display mode picker: XDA
     * feedback (NX123Dos, 2026-08-31) pointed out that every connect is a new display id, so a
     * per-display choice never survives -- while the real limit belongs to the cable/hub and is
     * the same for every screen. Set it once, forget it.
     */
    private TextView mDpHint;

    // The safe defaults every chooser starts with. After a screen has been scanned the lists are
    // the UNION of these and what that screen advertised (see populateFromSink) -- a 4K144 monitor
    // owner gets 2160p/1440p and 144/120 Hz as cap choices, a 1080p60 owner does not see junk.
    private static final int[] RES_DEFAULT = { 1080, 720, 480 };
    private static final int[] HZ_DEFAULT  = { 90, 60, 30 };

    private Spinner mDensitySpinner, mExactResSpinner;
    private Spinner mResSpinner, mHzSpinner;

    private static int indexOf(String[] values, String v) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
        return 0;
    }

    private static String resLabel(int h) {
        switch (h) {
            case 2160: return "2160p (4K)";
            case 1440: return "1440p";
            default:   return h + "p";
        }
    }

    /** Descending union of the defaults and what the last scanned sink offered. */
    private static int[] union(int[] base, int[] seen) {
        final java.util.TreeSet<Integer> set = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        for (int v : base) set.add(v);
        for (int v : seen) if (v > 0) set.add(v);
        final int[] out = new int[set.size()];
        int i = 0;
        for (int v : set) out[i++] = v;
        return out;
    }

    /**
     * (Re)build one chooser's list without losing the stored selection. The stored value may be
     * one that is no longer in the list (cap set on a different screen); it is kept as a choice
     * so the setting is never silently rewritten by opening this page.
     */
    private void fillCap(Spinner sp, String prop, int[] ladder, boolean res) {
        final String cur = Prop.get(prop, "auto");
        int curV = 0;
        try { if (!"auto".equals(cur)) curV = Integer.parseInt(cur.trim()); } catch (NumberFormatException ignored) {}
        final int[] all = curV > 0 ? union(ladder, new int[] { curV }) : ladder;
        final String[] labels = new String[all.length + 1];
        final String[] values = new String[all.length + 1];
        labels[0] = "Auto (max)"; values[0] = "auto";
        for (int i = 0; i < all.length; i++) {
            values[i + 1] = Integer.toString(all[i]);
            labels[i + 1] = res ? resLabel(all[i]) : all[i] + " Hz";
        }
        sp.setOnItemSelectedListener(null);   // adapter swap fires a selection; not a user act
        sp.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        sp.setSelection(indexOf(values, cur), false);
        sp.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                final String want = values[pos];
                if (want.equals(Prop.get(prop, "auto"))) return;   // includes the initial callback
                Prop.set(prop, want);
                applyDpCaps();
            }
        });
    }

    /**
     * XDA #67 (NX123Dos): "although default list include low safe values, after successful
     * device scanning, populate list with all possible display mode values". ExternalDisplay
     * remembers the last sink's heights/rates across unplugs, so the lists stay useful with no
     * screen attached.
     */
    private void populateFromSink() {
        fillExactRes(mExactResSpinner);
        final int[][] seen = ExternalDisplay.seenSink();
        fillCap(mResSpinner, ExternalDisplay.PROP_MAX_RES, union(RES_DEFAULT, seen[0]), true);
        fillCap(mHzSpinner,  ExternalDisplay.PROP_MAX_HZ,  union(HZ_DEFAULT,  seen[1]), false);
    }

    /**
     * DeX-style exact-resolution picker (user request 2026-09-05): every W x H the last scanned
     * screen advertised, aspect-labelled, largest first. Layered on the caps -- see
     * ExternalDisplay.PROP_RES -- so a screen that lacks the choice falls back to the limits.
     */
    private void fillExactRes(Spinner sp) {
        final String cur = Prop.get(ExternalDisplay.PROP_RES, "auto");
        final java.util.List<String> all = ExternalDisplay.seenResolutions(this);
        if (!"auto".equals(cur) && !all.contains(cur)) all.add(0, cur);   // keep an off-screen choice
        final String[] labels = new String[all.size() + 1];
        final String[] values = new String[all.size() + 1];
        // Short labels: the row label has weight 1 and a long spinner text squeezes it out
        // entirely (seen 2026-09-05: "Resolution" vanished behind a 40-char Auto label).
        labels[0] = "Auto";
        values[0] = "auto";
        for (int i = 0; i < all.size(); i++) {
            final String wh = all.get(i);
            values[i + 1] = wh;
            final int x = wh.indexOf('x');
            int w = 0, h = 0;
            try { w = Integer.parseInt(wh.substring(0, x)); h = Integer.parseInt(wh.substring(x + 1)); }
            catch (RuntimeException ignored) {}
            labels[i + 1] = w + "\u00d7" + h + " (" + ExternalDisplay.aspectLabel(w, h) + ")";
        }
        sp.setOnItemSelectedListener(null);
        sp.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        sp.setSelection(indexOf(values, cur), false);
        sp.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                final String want = values[pos];
                if (want.equals(Prop.get(ExternalDisplay.PROP_RES, "auto"))) return;
                Prop.set(ExternalDisplay.PROP_RES, want);
                applyDpCaps();
            }
        });
    }

    private Spinner capRow(LinearLayout page, String title) {
        LinearLayout row = labelledRow(page, title);
        Spinner sp = new Spinner(this);
        row.addView(sp);
        return sp;
    }

    private void displayRefreshRow(LinearLayout page) {
        mExactResSpinner = capRow(page, "Resolution");
        mResSpinner = capRow(page, "Max resolution");
        mHzSpinner = capRow(page, "Max refresh rate");
        populateFromSink();

        LinearLayout applyRow = labelledRow(page, "Apply to the connected screen");
        Button apply = new Button(this);
        apply.setText("Apply now");
        apply.setOnClickListener(v -> applyDpCaps());
        applyRow.addView(apply);

        mDpHint = new TextView(this);
        mDpHint.setTextSize(12);
        mDpHint.setAlpha(0.7f);
        mDpHint.setPadding(0, 0, 0, dp(8));
        page.addView(mDpHint);
        refreshDpHint(null);

        LinearLayout fixRow = labelledRow(page, "Screen lit but black after plugging in?");
        Button fix = new Button(this);
        fix.setText("Fix");
        fix.setOnClickListener(v -> kickDp());
        fixRow.addView(fix);
        addSwitch(page, "Do that on every connection", ExternalDisplay.PROP_AUTO_KICK, false);
        note(page, "Some docks with a DisplayPort-to-HDMI converter inside keep their HDMI output "
                + "set up for the last picture they showed and do not reset it when the phone "
                + "reconnects with the same mode: the phone sees a healthy link and sends a "
                + "picture, the monitor wakes up and stays black. The phone cannot detect this. "
                + "Fix switches the screen to its smallest mode for a moment and then back, which "
                + "makes the converter start over; a plain reconnect does not. If your dock needs "
                + "it every time, turn on the switch \u2014 it costs a few seconds of flicker on each "
                + "plug-in, so leave it off otherwise.");

        mDensitySpinner = capRow(page, "UI size (dpi at 1080p)");
        fillDensity(mDensitySpinner);

        LinearLayout sizeRow = labelledRow(page, "Fine-tune per monitor in Settings");
        Button size = new Button(this);
        size.setText("Open");
        size.setOnClickListener(v -> openExternalDisplaySettings());
        sizeRow.addView(size);
        note(page, "Text looks small or soft on a big monitor? That is the UI size, not the "
                + "signal. Android sizes an external screen by its physical pixel density, which "
                + "makes a 27\" 1080p monitor draw everything at about 0.7x (111 dpi); this ROM "
                + "defaults to the DeX-like 160 dpi at 1080p instead. The value here is for a "
                + "1080p screen and scales with resolution (1440p x1.33, 4K x2), so one choice "
                + "fits every monitor; it is applied to a screen the first time it connects. "
                + "The per-monitor slider in Settings > Connected devices > External displays > "
                + "Display size overrides it for that monitor and is remembered; picking a value "
                + "here again puts it back in charge. Auto uses the ROM default.");

        LinearLayout padRow = labelledRow(page, "Use the phone as a touchpad");
        Button pad = new Button(this);
        pad.setText("Start");
        pad.setOnClickListener(v -> {
            if (TouchpadActivity.externalDisplayId(this) == android.view.Display.INVALID_DISPLAY) {
                toast("Connect an external screen first");
                return;
            }
            startActivity(new android.content.Intent(this, TouchpadActivity.class));
        });
        padRow.addView(pad);
        LinearLayout monRow = labelledRow(page, "Monitor only (phone screen off, external screen stays on)");
        Button mon = new Button(this);
        mon.setText("Start");
        mon.setOnClickListener(v -> {
            if (TouchpadActivity.externalDisplayId(this) == android.view.Display.INVALID_DISPLAY) {
                toast("Connect an external screen first");
                return;
            }
            startService(new android.content.Intent(this, MonitorOnlyService.class));
        });
        monRow.addView(mon);
        fillTouchpadSpeed(capRow(page, "Pointer speed"));
        addSwitch(page, "Natural scrolling (content follows the fingers)", TouchpadActivity.PROP_NATURAL_SCROLL, true);
        note(page, "DeX-style: the phone screen goes black and becomes a trackpad for the "
                + "external screen, with a real mouse pointer there — for XR glasses or a TV, "
                + "where you start a video and then never look at the phone. One finger moves, "
                + "tap clicks, double-tap-and-hold drags, long press right-clicks, two fingers "
                + "scroll, a three-finger tap turns the phone screen off while the external screen "
                + "and the touchpad session stay up (power button or double-tap wakes it, unlocked). "
                + "Back (swipe in from a side edge) leaves, as does unplugging the screen. "
                + "Monitor only does the same phone-off trick without the touchpad; the phone wakes "
                + "by itself if the external screen is unplugged. Both are quick-settings tiles too.");
    }

    private void fillTouchpadSpeed(Spinner sp) {
        final String[] labels = new String[10];
        for (int i = 0; i < 10; i++) labels[i] = (i + 1) + (i == 4 ? "  (default)" : "");
        int cur = 5;
        try { cur = Integer.parseInt(Prop.get(TouchpadActivity.PROP_SPEED, "5").trim()); } catch (NumberFormatException ignored) {}
        cur = Math.max(1, Math.min(10, cur));
        sp.setOnItemSelectedListener(null);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        sp.setSelection(cur - 1, false);
        sp.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Prop.set(TouchpadActivity.PROP_SPEED, Integer.toString(pos + 1));
            }
        });
    }

    private static final int[] DENSITY_LADDER = { 120, 140, 160, 180, 200, 220, 240, 280, 320 };

    private void fillDensity(Spinner sp) {
        final String cur = Prop.get(ExternalDisplay.PROP_DENSITY, "auto");
        int curV = 0;
        try { if (!"auto".equals(cur)) curV = Integer.parseInt(cur.trim()); } catch (NumberFormatException ignored) {}
        final java.util.TreeSet<Integer> set = new java.util.TreeSet<>();   // ascending, unlike union()
        for (int v : DENSITY_LADDER) set.add(v);
        if (curV > 0) set.add(curV);
        final int[] all = new int[set.size()];
        { int i = 0; for (int v : set) all[i++] = v; }
        final String[] labels = new String[all.length + 1];
        final String[] values = new String[all.length + 1];
        labels[0] = "Auto (" + ExternalDisplay.DEFAULT_DENSITY_1080P + ")"; values[0] = "auto";
        for (int i = 0; i < all.length; i++) {
            values[i + 1] = Integer.toString(all[i]);
            labels[i + 1] = all[i] + (all[i] == ExternalDisplay.DEFAULT_DENSITY_1080P ? "  (default)"
                    : all[i] == 320 ? "  (2x)" : "");
        }
        sp.setOnItemSelectedListener(null);
        sp.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        sp.setSelection(indexOf(values, cur), false);
        sp.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                final String want = values[pos];
                if (want.equals(Prop.get(ExternalDisplay.PROP_DENSITY, "auto"))) return;
                Prop.set(ExternalDisplay.PROP_DENSITY, want);
                final int forced = ExternalDisplay.applyDensity(SettingsActivity.this, true);
                if (forced > 0) toast(forced + " dpi on the connected screen");
                else if ("auto".equals(want)) toast("Auto \u2014 ROM default on the connected screen");
                else toast("Saved \u2014 applies when a screen is connected");
            }
        });
    }

    /** Settings' per-monitor page (display size slider, resolution, rotation). */
    private void openExternalDisplaySettings() {
        android.content.Intent i = new android.content.Intent(
                "com.android.settings.EXTERNAL_DISPLAY_SETTINGS");
        i.setPackage("com.android.settings");
        try {
            startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            toast("External displays page not available in this Settings build");
        }
    }

    private void kickDp() {
        if (ExternalDisplay.kick(this)) {
            toast("Switching modes \u2014 the picture comes back in a few seconds");
        } else {
            toast("No external screen with a second mode to switch to");
        }
    }

    /** Resolve the caps against the attached screen and report honestly what happened. */
    private void applyDpCaps() {
        final ExternalDisplay.Result r = ExternalDisplay.apply(this, true);
        refreshDpHint(r);
        if (r.connected) populateFromSink();
        if (!r.connected) {
            toast("Saved \u2014 applies when a screen is connected");
        } else if (r.prefMissing) {
            toast("This screen does not offer " + Prop.get(ExternalDisplay.PROP_RES, "")
                    .replace("x", "\u00d7") + " \u2014 using the limits instead");
        } else if (!r.fits) {
            toast("No mode within the limits \u2014 using the screen's best instead");
        } else if (r.applied == null) {
            toast("Auto \u2014 using the screen's own preference");
        } else {
            toast(r.applied.label());
        }
    }

    private void refreshDpHint(ExternalDisplay.Result r) {
        if (mDpHint == null) return;
        final String base = "Resolution lists every mode the last connected screen advertised (Auto only "
                + "until a screen has been scanned once); pick one "
                + "and it is used whenever a screen offers it, at the fastest rate under the refresh "
                + "limit. The two limits are caps for ANY screen (a hub that only carries 1080p60 is a "
                + "property of the cable, so set it once). Lower the RESOLUTION first \u2014 measured "
                + "on this hardware, 1080p at 50 Hz and 60 Hz both run at 148.5 MHz because the "
                + "50 Hz timing just has wider blanking, while 720p60 is half that.";
        String state = "";
        if (r != null) {
            if (!r.connected) {
                state = "\n\nNo external screen detected right now. Limits are applied "
                        + (ExternalDisplay.settleMs() / 1000) + " s after a screen settles.";
            } else if (r.applied != null && r.ladder != null && !r.ladder.isEmpty()) {
                final ExternalDisplay.Mode best = r.ladder.get(0);
                state = "\n\nConnected screen: " + r.ladder.size() + " modes, best "
                        + best.label() + " \u2014 " + (r.fits
                        ? "set to " + r.applied.label() + "."
                        : "nothing within these limits, so its best mode is used instead rather "
                          + "than losing the picture.");
            } else if (!r.fits) {
                state = "\n\nThe connected screen offers nothing within these limits, so its best "
                        + "mode is used instead rather than losing the picture.";
            } else if (r.applied != null) {
                state = "\n\nConnected screen set to " + r.applied.label() + ".";
            }
        }
        mDpHint.setText(base + state);
        mDpHint.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mDpHint != null) {
            final ExternalDisplay.Result r = ExternalDisplay.apply(this, false);
            refreshDpHint(r);
            if (r.connected) populateFromSink();
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
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
    private static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length); System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static int[] concat(int[] a, int[] b) {
        int[] r = new int[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length); System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static int colNamesLen(int position) {
        return (position == 3) ? COL_NAMES.length + RGB_NAMES.length : COL_NAMES.length;
    }

    // ---------- game super-resolution / frame interpolation (Qualcomm GPP) ----------
    private static final String[] GPP_UPSCALE_NAMES = {"Off", "Moderate", "Strong"};

    private void gppSection(LinearLayout root) {
        header(root, "Super-resolution & frame interpolation (NPU)");
        note(root, "The stock \"R4 gaming chip\" feature: Qualcomm Game Post Processing on the "
                + "Hexagon NPU. Games render at their own resolution and frame rate; the NPU "
                + "upscales each frame and/or generates in-between frames before the screen sees "
                + "them. Works on the games Qualcomm lists for this chip (Genshin, Honkai, PUBG, "
                + "CoD Mobile, Wuthering Waves, Zenless Zone Zero, … — see "
                + "/system/etc/gpp_app_list); nothing else is touched. Experimental on this ROM: "
                + "if a game glitches or stutters, turn it off here and it stops immediately, "
                + "even mid-game.");

        LinearLayout row = labelledRow(root, "Enable for listed games");
        final Switch main = new Switch(this);
        main.setChecked(Prop.getBool(GamePostProcessing.PROP_ENABLED, GamePostProcessing.DEF_ENABLED));
        row.addView(main);

        // Which of Qualcomm's games this phone actually has, so the switch is not a mystery.
        final java.util.List<GamePostProcessing.Entry> list = GamePostProcessing.readAppList();
        final java.util.List<String> have = GamePostProcessing.installedListedGames(this);
        if (have.isEmpty()) {
            note(root, "None of the supported games is installed right now; the switch will do "
                    + "nothing until one is.");
        } else {
            note(root, "Supported and installed on this phone: " + String.join(", ", have) + ".");
        }
        LinearLayout listRow = labelledRow(root, "All supported games");
        Button show = new Button(this);
        show.setText("Show " + list.size());
        show.setOnClickListener(v -> showGppList(list));
        listRow.addView(show);

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setVisibility(main.isChecked() ? View.VISIBLE : View.GONE);
        root.addView(box);

        LinearLayout interpRow = labelledRow(box, "Frame interpolation");
        Switch interp = new Switch(this);
        interp.setChecked(Prop.getBool(GamePostProcessing.PROP_INTERP, GamePostProcessing.DEF_INTERP));
        interp.setOnCheckedChangeListener((v, on) -> {
            Prop.set(GamePostProcessing.PROP_INTERP, on ? "1" : "0");
            GamePostProcessing.apply();
        });
        interpRow.addView(interp);
        note(box, "Doubles the perceived frame rate by generating a frame between every two the "
                + "game draws. Adds about one frame of input latency.");

        LinearLayout upRow = labelledRow(box, "Super-resolution");
        final Spinner up = new Spinner(this);
        up.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                GPP_UPSCALE_NAMES));
        up.setSelection(clamp(parseInt(Prop.get(GamePostProcessing.PROP_UPSCALE,
                Integer.toString(GamePostProcessing.DEF_UPSCALE)), GamePostProcessing.DEF_UPSCALE), 0, 2));
        up.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Prop.set(GamePostProcessing.PROP_UPSCALE, Integer.toString(pos));
                GamePostProcessing.apply();
            }
        });
        upRow.addView(up);
        note(box, "Lets the game render at a lower resolution and sharpens it back up on the NPU. "
                + "Only kicks in when the game's own resolution is below 1080 x 2400.");

        LinearLayout perRow = labelledRow(box, "Per-game switches");
        Button per = new Button(this);
        per.setText("Edit");
        per.setOnClickListener(v -> showGppPerGame());
        perRow.addView(per);
        note(box, "Turn it off for one title (emulators such as Dolphin, AetherSX2 or Winlator "
                + "mostly get worse with it) or add a game that is not on Qualcomm's list. The "
                + "choice is pushed to the NPU pipeline whenever that app comes to the front, so "
                + "it also applies mid-game.");

        LinearLayout allRow = labelledRow(box, "Try it on every game");
        Switch all = new Switch(this);
        all.setChecked(Prop.getBool(GamePostProcessing.PROP_ALLGAME, GamePostProcessing.DEF_ALLGAME));
        all.setOnCheckedChangeListener((v, on) -> {
            Prop.set(GamePostProcessing.PROP_ALLGAME, on ? "1" : "0");
            GamePostProcessing.apply();
        });
        allRow.addView(all);
        note(box, "Ignores Qualcomm's list and offers post-processing to any app that draws with "
                + "OpenGL into a SurfaceView — most games, and some video players and emulators. "
                + "Untested titles may flicker or crash. It only helps a game that runs below "
                + "your screen's refresh rate (a 30 or 60 fps cap becomes 60 or 120); a game "
                + "that already hits 120 fps gains nothing and just pays the NPU's extra "
                + "~0.25 W. Keep this off unless you are experimenting.");

        main.setOnCheckedChangeListener((v, on) -> {
            Prop.set(GamePostProcessing.PROP_ENABLED, on ? "1" : "0");
            box.setVisibility(on ? View.VISIBLE : View.GONE);
            GamePostProcessing.apply();
        });
    }

    /**
     * Qualcomm's allowlist as a searchable dialog: store names first, Chinese-store builds by
     * package. The filter matches the visible text, so "genshin", "miHoYo" or "upscale" all work.
     */
    private void showGppList(java.util.List<GamePostProcessing.Entry> list) {
        final java.util.List<String> named = new java.util.ArrayList<>();
        final java.util.List<String> other = new java.util.ArrayList<>();
        for (GamePostProcessing.Entry e : list) {
            final String installed = GamePostProcessing.installedLabel(this, e.pkg);
            final String known = GamePostProcessing.knownName(e.pkg);
            final String fx = (e.interp ? "frames" : "") + (e.interp && e.upscale > 0 ? " + " : "")
                    + (e.upscale > 0 ? (e.upscale == 2 ? "strong upscale" : "upscale") : "");
            final String tag = (installed != null ? "  \u2713 installed" : "")
                    + (fx.isEmpty() ? "" : "  [" + fx + "]");
            // Package always present so a search by package name hits too.
            if (known != null) named.add(known + tag + "\n" + e.pkg);
            else if (installed != null) named.add(installed + tag + "\n" + e.pkg);
            else other.add(e.pkg + tag);
        }
        java.util.Collections.sort(named, String.CASE_INSENSITIVE_ORDER);
        java.util.Collections.sort(other, String.CASE_INSENSITIVE_ORDER);
        final java.util.List<String> all = new java.util.ArrayList<>(named);
        all.addAll(other);

        final int pad = dp(16);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, 0);

        final TextView intro = new TextView(this);
        intro.setText("Qualcomm's list for this chip. Per game: in-between frames, upscale, or "
                + "both. " + named.size() + " store titles first, then " + other.size()
                + " Chinese-store builds by package.");
        intro.setTextSize(13);
        box.addView(intro);

        final android.widget.EditText search = new android.widget.EditText(this);
        search.setHint("Search name or package");
        search.setSingleLine(true);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(search);

        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new java.util.ArrayList<>(all));
        final android.widget.ListView lv = new android.widget.ListView(this);
        lv.setAdapter(adapter);
        lv.setTextFilterEnabled(false);
        // Fixed height: a weight-1 child collapses inside a wrap-content dialog.
        box.addView(lv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420)));

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            public void onTextChanged(CharSequence c, int a, int b, int d) {}
            public void afterTextChanged(android.text.Editable ed) {
                final String q = ed.toString().trim().toLowerCase(java.util.Locale.ROOT);
                adapter.clear();
                for (String row : all) {
                    if (q.isEmpty() || row.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                        adapter.add(row);
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });

        final StringBuilder sb = new StringBuilder();
        for (String n : all) sb.append(n.replace('\n', ' ')).append('\n');
        final String plain = sb.toString();
        new android.app.AlertDialog.Builder(this)
                .setTitle("Supported games (" + list.size() + ")")
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("Copy all", (d, w) -> {
                    final android.content.ClipboardManager cm =
                            getSystemService(android.content.ClipboardManager.class);
                    if (cm != null) cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("gpp games", plain));
                })
                .show();
    }

    /**
     * Per-game switches: every installed app GPP would look at (Qualcomm's list, or everything
     * with "Try it on every game") plus the ones the user added, each with an on/off switch, and
     * an "Add app" picker for unlisted titles. Overrides are kept in GamePostProcessing's prefs.
     */
    private void showGppPerGame() {
        final PackageManager pm = getPackageManager();
        final boolean allgame = Prop.getBool(GamePostProcessing.PROP_ALLGAME, GamePostProcessing.DEF_ALLGAME);
        // Candidate set: installed listed games + explicit overrides (either direction).
        final java.util.TreeMap<String, String> rows = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final java.util.Set<String> pkgs = new java.util.HashSet<>();
        for (GamePostProcessing.Entry e : GamePostProcessing.readAppList()) pkgs.add(e.pkg);
        pkgs.addAll(GamePostProcessing.forcedOn(this));
        for (String pkg : launchablePackages(pm)) {
            if (GamePostProcessing.overrideOf(this, pkg) != 0) pkgs.add(pkg);
        }
        for (String pkg : pkgs) {
            final String l = GamePostProcessing.installedLabel(this, pkg);
            if (l == null) continue;                                   // not installed
            final String known = GamePostProcessing.knownName(pkg);
            rows.put((known != null ? known : l) + "\n" + pkg, pkg);
        }

        final int pad = dp(16);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, 0);
        final TextView intro = new TextView(this);
        intro.setText(rows.isEmpty()
                ? "No supported game is installed. Add an app below to try the NPU on it."
                : (allgame ? "\"Try it on every game\" is on, so anything not switched off "
                        + "here gets it too." : "Installed titles from Qualcomm's list, plus "
                        + "the apps you added."));
        intro.setTextSize(13);
        box.addView(intro);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (java.util.Map.Entry<String, String> r : rows.entrySet()) {
            final String pkg = r.getValue();
            final boolean listed = GamePostProcessing.isListed(pkg);
            LinearLayout row = labelledRow(list, r.getKey());
            ((TextView) row.getChildAt(0)).setTextSize(14);
            final Switch sw = new Switch(this);
            final Boolean d = GamePostProcessing.decide(this, pkg);
            sw.setChecked(d != null && d);
            sw.setOnCheckedChangeListener((v, on) -> {
                // Default already means "on" for listed / allgame apps; anything else needs
                // an explicit entry. Off is only an explicit entry when default would be on.
                final boolean defOn = listed || allgame;
                GamePostProcessing.setOverride(this, pkg, on ? (defOn ? 0 : 1) : (defOn ? -1 : 0));
            });
            row.addView(sw);
        }
        final ScrollView sv = new ScrollView(this);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(rows.isEmpty() ? 0 : 360)));

        new android.app.AlertDialog.Builder(this)
                .setTitle("Per-game switches")
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("Add app\u2026", (dlg, w) -> showGppAddApp(pm, pkgs))
                .show();
    }

    /** Searchable picker over launchable apps not already in the per-game list; tap = add. */
    private void showGppAddApp(PackageManager pm, java.util.Set<String> already) {
        showAppPicker(pm, already, pkg -> {
            GamePostProcessing.setOverride(this, pkg, 1);
            Toast.makeText(this, "NPU post-processing on for " + pkg, Toast.LENGTH_SHORT).show();
            showGppPerGame();
        });
    }

    /** Searchable picker over launchable apps (minus {@code already} and ourselves); tap = pick. */
    private void showAppPicker(PackageManager pm, java.util.Set<String> already,
            java.util.function.Consumer<String> onPick) {
        final java.util.List<String> all = new java.util.ArrayList<>();
        final java.util.Map<String, String> byRow = new java.util.HashMap<>();
        for (String pkg : launchablePackages(pm)) {
            if (already.contains(pkg) || pkg.equals(getPackageName())) continue;
            final String l = GamePostProcessing.installedLabel(this, pkg);
            final String row = (l == null ? pkg : l) + "\n" + pkg;
            all.add(row); byRow.put(row, pkg);
        }
        java.util.Collections.sort(all, String.CASE_INSENSITIVE_ORDER);

        final int pad = dp(16);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, 0);
        final android.widget.EditText search = new android.widget.EditText(this);
        search.setHint("Search name or package");
        search.setSingleLine(true);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(search);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new java.util.ArrayList<>(all));
        final android.widget.ListView lv = new android.widget.ListView(this);
        lv.setAdapter(adapter);
        box.addView(lv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420)));
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            public void onTextChanged(CharSequence c, int a, int b, int d) {}
            public void afterTextChanged(android.text.Editable ed) {
                final String q = ed.toString().trim().toLowerCase(java.util.Locale.ROOT);
                adapter.clear();
                for (String row : all) {
                    if (q.isEmpty() || row.toLowerCase(java.util.Locale.ROOT).contains(q)) adapter.add(row);
                }
                adapter.notifyDataSetChanged();
            }
        });
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Add an app")
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        lv.setOnItemClickListener((p, v, pos, id) -> {
            final String pkg = byRow.get(adapter.getItem(pos));
            dlg.dismiss();
            if (pkg != null) onPick.accept(pkg);
        });
    }

    private static java.util.List<String> launchablePackages(PackageManager pm) {
        final java.util.Set<String> out = new java.util.LinkedHashSet<>();
        final android.content.Intent probe = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
        }
        return new java.util.ArrayList<>(out);
    }

    private void header(LinearLayout root, String text) {
        TextView h = new TextView(this);
        h.setText(text.toUpperCase());
        h.setTextSize(13);
        h.setTextColor(accentColor());
        h.setPadding(0, dp(20), 0, dp(6));
        root.addView(h);
    }

    // ---------- level spinner row (fan/pump) ----------
    // ---------- GPU profile while gaming ----------
    // Only takes effect while a game is in the foreground (GameSpace sets
    // persist.sys.power_mode_perf); vendor_init writes the MHz to the GMU DCVS tunable
    // min_freq_mhz and resets it to -1 on exit. Values are real pwrlevels from this
    // unit's fused GPU speed bin (qcom,gpu-pwrlevels-1, speed-bin 0xfc): 1200 is the
    // hardware maximum. The kernel does NOT range-check this node -- it accepted 9999
    // verbatim when tested -- so the UI only ever offers table entries.
    // One prop carries the whole state: MHz, or -1 for Balanced (also the kernel's own
    // reset value). No separate enable flag -- there was one, and gating the rc RESET on
    // it left the GPU pinned at the old floor when the user picked Balanced.
    private static final String PROP_GPU_MHZ = "persist.sys.rm.gamegpu.mhz";

    private static final String[] GPU_PROFILE_NAMES =
            {"Balanced", "Performance", "Max performance", "Ultimate (1200 MHz)"};
    private static final int[] GPU_PROFILE_MHZ = {-1, 902, 1050, 1200};
    private static final String[] GPU_PROFILE_DESC = {
        "Stock behaviour. The GPU picks its own clock from 160 MHz up to 1200 MHz as the "
            + "game demands. Coolest, longest battery life, and already fast \u2014 on this "
            + "phone the GPU does reach full speed on its own. A light game that sits at 160 MHz "
            + "here is not stuck: the GPU is not the bottleneck, so it has no reason to clock up.",
        "Keeps the GPU at 902 MHz or above so it never falls into the low steps between "
            + "frames. Small heat cost. Helps most where frame times are uneven rather than "
            + "simply low.",
        "Keeps the GPU at 1050 MHz or above. Noticeably warmer and thirstier. Worth trying "
            + "in heavy 3D titles; wasted on light ones.",
        "Pins the GPU to its 1200 MHz maximum the whole time the game is open \u2014 menus, "
            + "loading screens and idle moments included. It never clocks down."
    };
    private static final String GPU_ULTIMATE_WARNING =
        "\u26a0 Ultimate holds full clock no matter what the game is doing. The phone will "
            + "get hot fast, the battery drains far quicker, and sustained use ages the "
            + "battery. It also fights the skin-temperature limiter, which still cuts the GPU "
            + "to 826 MHz once the case passes 40 \u00b0C \u2014 so on an already-warm phone "
            + "this mostly burns power without adding speed. Meant for short benchmark runs, "
            + "not for playing.";

    private void gpuProfileRow(LinearLayout root) {
        LinearLayout row = labelledRow(root, "GPU profile while gaming");
        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                GPU_PROFILE_NAMES));

        final TextView desc = new TextView(this);
        desc.setTextSize(12);
        desc.setAlpha(0.7f);
        desc.setPadding(0, 0, 0, dp(4));

        final TextView warn = new TextView(this);
        warn.setTextSize(12);
        warn.setTextColor(Color.parseColor("#FFB4A9"));
        warn.setText(GPU_ULTIMATE_WARNING);
        warn.setPadding(0, 0, 0, dp(8));
        warn.setVisibility(View.GONE);

        sp.setSelection(idxOf(GPU_PROFILE_MHZ, parseInt(Prop.get(PROP_GPU_MHZ, "-1"), -1)));
        desc.setText(GPU_PROFILE_DESC[sp.getSelectedItemPosition()]);
        warn.setVisibility(sp.getSelectedItemPosition() == 3 ? View.VISIBLE : View.GONE);

        sp.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                // Writing the prop is enough: the rc trigger matches any non-empty value
                // and re-applies immediately, so switching profile mid-game takes effect
                // without waiting for the next game start.
                Prop.set(PROP_GPU_MHZ, Integer.toString(GPU_PROFILE_MHZ[pos]));
                desc.setText(GPU_PROFILE_DESC[pos]);
                warn.setVisibility(pos == 3 ? View.VISIBLE : View.GONE);
            }
        });

        row.addView(sp);
        root.addView(desc);
        root.addView(warn);

        // The profile only decides WHAT the floor is -- GameSpace decides WHEN it applies.
        // Nothing else surfaces that, so a user can pick a profile here and have it silently
        // never engage. Say so, and name the games we can see that would need adding.
        final TextView gs = new TextView(this);
        gs.setTextSize(12);
        gs.setPadding(0, 0, 0, dp(10));
        gs.setText(gameSpaceStatus());
        root.addView(gs);
    }

    /** Explains the GameSpace dependency, and names installed games not set to Performance. */
    private String gameSpaceStatus() {
        final java.util.Map<String, String> listed = new java.util.HashMap<>();
        String raw = null;
        try {
            raw = Settings.System.getString(getContentResolver(), "gamespace_game_list");
        } catch (Exception ignored) { }
        if (raw != null && !raw.isEmpty()) {
            for (String entry : raw.split(";")) {
                final String[] parts = entry.split("=", 2);
                if (parts.length == 2) listed.put(parts[0].trim(), parts[1].trim());
            }
        }
        // "2" is GameListManager.PERF_MODE_VALUE -- being in the list alone is not enough,
        // only the per-game Performance toggle triggers the boost.
        int inPerf = 0;
        for (String v : listed.values()) if ("2".equals(v)) inPerf++;

        final java.util.List<String> missing = new java.util.ArrayList<>();
        try {
            final PackageManager pm = getPackageManager();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (ai.category != ApplicationInfo.CATEGORY_GAME) continue;
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (!"2".equals(listed.get(ai.packageName))) {
                    missing.add(String.valueOf(pm.getApplicationLabel(ai)));
                }
            }
        } catch (Exception ignored) { }

        final StringBuilder sb = new StringBuilder();
        if (inPerf == 0) {
            sb.append("Not active yet. This profile only applies to games added in Game Space "
                    + "with Performance mode switched on for that game \u2014 no game is set up "
                    + "that way, so the GPU currently keeps its stock behaviour.");
        } else {
            sb.append(inPerf).append(inPerf == 1 ? " game is" : " games are")
              .append(" set to Performance in Game Space; the profile applies to ")
              .append(inPerf == 1 ? "it." : "those.");
        }
        if (!missing.isEmpty()) {
            java.util.Collections.sort(missing);
            sb.append("\n\nGames found on this phone that are not set up: ");
            for (int i = 0; i < missing.size() && i < 5; i++) {
                if (i > 0) sb.append(", ");
                sb.append(missing.get(i));
            }
            if (missing.size() > 5) sb.append(" and ").append(missing.size() - 5).append(" more");
            sb.append(". Add them in Game Space and turn on Performance mode for each.");
        }
        return sb.toString();
    }

    // ---------- Remote Key Provisioning ----------
    // A device whose factory attestation keybox is lost or corrupt (a real hazard on a
    // custom ROM) can pull a fresh key from Google's provisioning server instead. This is
    // the same path `cmd remote_provisioning certify default` takes, minus the shell:
    // rkpdapp only accepts binds from SYSTEM_UID or itself
    // (RemoteProvisioningService.java:70), and this app is android.uid.system, so it can
    // call android.security.rkp.IRemoteProvisioning directly. The AIDL lives in
    // frameworks/base/core/java, i.e. in framework.jar, which platform_apis:true reaches.
    //
    // It provisions automatically once remote_provisioning.hostname is set (see
    // product.prop); this button is for when it has not, and to show why.
    private static final String RKP_SERVICE = "remote_provisioning";
    // rkpdapp wants the full AIDL instance name; `cmd remote_provisioning certify default`
    // prepends the descriptor itself (RemoteProvisioningShellCommand.getRegistrationProxy).
    private static final String RKP_IRPC =
            "android.hardware.security.keymint.IRemotelyProvisionedComponent/default";
    // Same arbitrary key id RemoteProvisioningShellCommand uses, so this exercises the
    // identical path rather than a subtly different one.
    private static final int RKP_KEY_ID = 452436;

    private void rkpRow(LinearLayout root) {
        final TextView status = new TextView(this);
        status.setTextSize(12);
        status.setAlpha(0.7f);
        status.setPadding(0, 0, 0, dp(8));

        // The switch, not a bare button: RKP has an ongoing cost (rkpdapp wakes the phone daily to
        // top up its key pool), so it must be something the user can turn back off.
        LinearLayout swRow = labelledRow(root, "Remote key provisioning");
        final Switch sw = new Switch(this);
        sw.setChecked(Rkp.isEnabled());
        swRow.addView(sw);

        LinearLayout row = labelledRow(root, "Attestation keys");
        final Button go = new Button(this);
        go.setText("Re-provision");
        row.addView(go);
        root.addView(status);

        final String offText =
                "This phone has no attestation keys of its own \u2014 KeyMint reports "
                + "ATTESTATION_KEYS_NOT_PROVISIONED and the keybox store is empty, so apps that "
                + "check hardware attestation fail. Turning this on lets the phone fetch signed "
                + "keys from a provisioning server and gives it a real Google-rooted certificate "
                + "chain. Verified working on this device.\n\n"
                + "PRIVACY \u2014 read before turning this on. The request your phone sends is "
                + "signed by its secure hardware and identifies the device: a permanent per-device "
                + "ID, make and model, the security patch levels, and the fact that the bootloader "
                + "is unlocked. Google issues the certificates, so Google can log that identifier "
                + "against this specific handset, and can revoke it. The server used is the "
                + "GrapheneOS proxy, which keeps your IP address from Google \u2014 but not the "
                + "device identity, because Google still signs the keys. Afterwards, any app that "
                + "asks for hardware attestation receives a certificate chain that points at this "
                + "phone.\n\n"
                + "That is the trade: apps needing hardware attestation start working, in exchange "
                + "for a stable hardware identity that Google can see. Off by default for that "
                + "reason.\n\n"
                + "It does NOT make the phone look locked. The attestation is signed by the secure "
                + "hardware and truthfully reports the unlocked bootloader, so apps that demand a "
                + "locked device \u2014 Google Wallet tap-to-pay, strong Play Integrity, some "
                + "banking apps \u2014 will still refuse. This only fixes \"the device has no "
                + "attestation keys at all\"; it cannot hide that the bootloader is unlocked.\n\n"
                + "Also costs a little battery: the system wakes about once a day to keep the key "
                + "pool topped up. Needs internet.";
        final String onText =
                "On. Keys are fetched from " + "remoteprovisioning.grapheneos.org" + " when an app "
                + "needs one. Use Re-provision only if attestation is failing \u2014 it requests a "
                + "key now and reports what comes back.";

        status.setText(Rkp.isEnabled() ? onText : offText);
        go.setEnabled(Rkp.isEnabled());

        sw.setOnCheckedChangeListener((v, on) -> {
            Rkp.apply(getApplicationContext(), on);
            go.setEnabled(on);
            bumpRev();
            if (!on) {
                status.setText(offText);
                toast("Remote key provisioning off");
                return;
            }
            // Don't just claim it is on -- go and find out. init has to pick up the property and
            // rkpdapp has to re-store its url (Rkp.apply waits up to 3 s for the hostname, then
            // re-delivers BOOT_COMPLETED to it) before a request can succeed, hence the delay.
            status.setText("Turning on\u2026 asking the provisioning server for a key. "
                    + "This needs internet and takes a few seconds.");
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> rkpCertify(status, go), 4000);
        });

        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                go.setEnabled(false);
                status.setText("Requesting a key\u2026");
                rkpCertify(status, go);
            }
        });
    }

    private void rkpCertify(final TextView status, final Button go) {
        try {
            IRemoteProvisioning rp = IRemoteProvisioning.Stub.asInterface(
                    ServiceManager.getService(RKP_SERVICE));
            if (rp == null) {
                rkpDone(status, go, "The remote_provisioning service is not running.");
                return;
            }
            rp.getRegistration(RKP_IRPC, new IGetRegistrationCallback.Stub() {
                public void onSuccess(IRegistration reg) {
                    try {
                        reg.getKey(RKP_KEY_ID, new IGetKeyCallback.Stub() {
                            public void onSuccess(RemotelyProvisionedKey key) {
                                int n = (key == null || key.encodedCertChain == null)
                                        ? 0 : key.encodedCertChain.length;
                                rkpDone(status, go, n > 0
                                        ? "Working. The phone was issued an attestation key and "
                                          + "received a " + n + "-byte certificate chain, so "
                                          + "hardware attestation is now available to apps.\n\n"
                                          + rkpChainSummary(key.encodedCertChain)
                                        : "The server answered but returned no certificate chain "
                                          + "\u2014 press Re-provision to try again.");
                            }
                            public void onCancel() { rkpDone(status, go, "Cancelled."); }
                            public void onError(byte error, String description) {
                                rkpDone(status, go, rkpExplain(error, description));
                            }
                        });
                    } catch (Exception e) {
                        rkpDone(status, go, "Failed: " + e);
                    }
                }
                public void onCancel() { rkpDone(status, go, "Cancelled."); }
                public void onError(String error) { rkpDone(status, go, "Failed: " + error); }
            });
        } catch (Exception e) {
            rkpDone(status, go, "Failed: " + e);
        }
    }

    /**
     * Turn an IGetKeyCallback.ErrorCode into something a user can act on.
     *
     * The codes are from frameworks/base IGetKeyCallback.aidl. ERROR_PERMANENT (4) is the one that
     * matters most here: it means the manufacturer never registered this handset with the RKP
     * backend (or it was revoked), so no amount of retrying will help. That distinction is the
     * whole point of showing a status -- we verified one NX809J whose keys ARE enrolled, but we
     * cannot know that every unit is, and a user needs to be told which case they are in rather
     * than left with a switch that silently does nothing.
     */
    private static String rkpExplain(byte error, String description) {
        switch (error) {
            case 4: // ERROR_PERMANENT
                return "This phone cannot get attestation keys. Its maker never registered it "
                        + "with the provisioning backend (or the keys were revoked), so this will "
                        + "never work on this handset \u2014 nothing in the ROM can change that. "
                        + "Turn the switch back off so it stops retrying daily.\n\n(" + description + ")";
            case 3: // ERROR_PENDING_INTERNET_CONNECTIVITY
                return "No internet reachable. The key pool is empty and the provisioning server "
                        + "could not be contacted \u2014 connect to WiFi or mobile data and press "
                        + "Re-provision again.";
            case 2: // ERROR_REQUIRES_SECURITY_PATCH
                return "Refused: the provisioning server considers this build's security patch "
                        + "level too old to issue keys. A newer build is needed.";
            default: // ERROR_UNKNOWN
                return "Failed: " + description + "\n\nThis one is not a known permanent failure "
                        + "\u2014 press Re-provision to try again.";
        }
    }

    /**
     * The chain as people, not bytes: one line per certificate, leaf first, ending in the
     * root -- so a reader can see for themselves that it ends at Google's Key Attestation CA
     * rather than take "working" on trust. XDA #67 (NX123Dos): "the feature needs clear output,
     * at least alert window, to see the result".
     */
    private static String rkpChainSummary(byte[] der) {
        try {
            final java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            final java.util.Collection<? extends java.security.cert.Certificate> certs =
                    cf.generateCertificates(new java.io.ByteArrayInputStream(der));
            final StringBuilder sb = new StringBuilder("Certificate chain (" + certs.size() + "):");
            int i = 0;
            for (java.security.cert.Certificate c : certs) {
                final java.security.cert.X509Certificate x = (java.security.cert.X509Certificate) c;
                sb.append("\n  ").append(++i).append(". ")
                        .append(rdn(x.getSubjectX500Principal().getName()));
                sb.append("\n      issued by ")
                        .append(rdn(x.getIssuerX500Principal().getName()));
                sb.append(", until ")
                        .append(java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT)
                                .format(x.getNotAfter()));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "Certificate chain could not be parsed: " + t;
        }
    }

    /** "CN=Droid CA3,O=Google LLC,..." -> "Droid CA3 (Google LLC)". */
    private static String rdn(String dn) {
        String cn = null, o = null, serial = null;
        for (String part : dn.split(",")) {
            final String p = part.trim();
            if (p.startsWith("CN=")) cn = p.substring(3);
            else if (p.startsWith("O=")) o = p.substring(2);
            else if (p.startsWith("SERIALNUMBER=") || p.startsWith("2.5.4.5=")) serial = "serial";
        }
        if (cn == null) cn = serial != null ? "device key (" + serial + ")" : dn;
        return o == null ? cn : cn + " (" + o + ")";
    }

    // The AIDL is oneway, so every callback lands on a binder thread. The result also goes into
    // a dialog: the status line under the switch is small and easy to miss.
    private void rkpDone(final TextView status, final Button go, final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                status.setText(msg);
                go.setEnabled(true);
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Attestation keys")
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton("Copy", (d, w) -> {
                            final android.content.ClipboardManager cm =
                                    getSystemService(android.content.ClipboardManager.class);
                            if (cm != null) cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("attestation", msg));
                        })
                        .show();
            }
        });
    }

    /**
     * Cooling fan: Off / Auto / 1..5 in ONE control.
     *
     * "Auto" used to be a separate switch sitting next to a fixed-speed dropdown, which is two
     * controls for one decision and let the user set both (the switch silently won, because
     * hwcontrol overwrites persist.sys.fan.level from the temperature curve). Folding Auto in as
     * a level makes the exclusivity obvious and unrepresentable-if-wrong.
     *
     * Auto is not a value the hardware understands: persist.sys.rm.fan_auto=1 makes hwcontrol
     * write persist.sys.fan.level itself, and the `on property:persist.sys.fan.level=N` triggers
     * in redmagic_hw_arm.rc do the actual /sys/kernel/fan write either way.
     */
    private void fanLevelRow(LinearLayout root, String title) {
        final String levelKey = "persist.sys.fan.level";
        final String autoKey  = "persist.sys.rm.fan_auto";
        final String[] names  = {"Off", "Auto", "1", "2", "3", "4", "5"};

        LinearLayout row = labelledRow(root, title);
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        final int lvl = clamp(parseInt(Prop.get(levelKey, "0"), 0), 0, 5);
        sp.setSelection(Prop.getBool(autoKey, true) ? 1 : (lvl == 0 ? 0 : lvl + 1));
        sp.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == 1) {
                    Prop.set(autoKey, "1");          // hwcontrol takes over the level
                } else {
                    Prop.set(autoKey, "0");
                    Prop.set(levelKey, Integer.toString(pos == 0 ? 0 : pos - 1));
                }
                bumpRev();
            }
        });
        row.addView(sp);
    }

    /**
     * Fan speed while fast charging: Off / Auto / 1..5.
     *
     * "Auto" is stored as the literal string "auto" rather than a number, because there is no fan
     * level that means "follow the curve". ChargeCooling turns that into chargecool.active=2,
     * which redmagic_hw_arm.rc handles by raising the fan rail and the pump but NOT pinning
     * fan_speed_level -- so hwcontrol's temperature curve keeps driving it, exactly as it does
     * when not charging. active=1 remains the fixed-speed case.
     */
    private void chargeFanLevelRow(LinearLayout root, String title) {
        final String[] names = {"Off", "Auto", "1", "2", "3", "4", "5"};
        LinearLayout row = labelledRow(root, title);
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        final String cur = Prop.get(ChargeCooling.PROP_FAN_LEVEL, ChargeCooling.FAN_AUTO).trim();
        if (ChargeCooling.FAN_AUTO.equals(cur)) {
            sp.setSelection(1);
        } else {
            final int n = clamp(parseInt(cur, 0), 0, 5);
            sp.setSelection(n == 0 ? 0 : n + 1);
        }
        sp.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Prop.set(ChargeCooling.PROP_FAN_LEVEL,
                        pos == 1 ? ChargeCooling.FAN_AUTO
                                 : Integer.toString(pos == 0 ? 0 : pos - 1));
                // Re-publish now: the mode (fixed vs auto) changes which active value we request,
                // and battery broadcasts can be ~90 s apart.
                ChargeCooling.reevaluate(getApplicationContext());
                bumpRev();
            }
        });
        row.addView(sp);
    }

    /**
     * Fan speed while gaming: Off / Auto / 1..5, stored the same way as the charging row.
     *
     * gameperfd is the single owner of game-mode cooling (redmagic_hw_arm.rc no longer writes
     * fan/pump for game mode), so this value is read there: "auto" leaves the temperature curve
     * running, a number pins that level, and "Cool while gaming" off means it touches neither.
     */
    private void gameFanLevelRow(LinearLayout root, String title) {
        final String key = "persist.sys.rm.gamecool.fan";
        final String[] names = {"Off", "Auto", "1", "2", "3", "4", "5"};
        LinearLayout row = labelledRow(root, title);
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        final String cur = Prop.get(key, ChargeCooling.FAN_AUTO).trim();
        if (ChargeCooling.FAN_AUTO.equals(cur)) {
            sp.setSelection(1);
        } else {
            final int n = clamp(parseInt(cur, 0), 0, 5);
            sp.setSelection(n == 0 ? 0 : n + 1);
        }
        sp.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Prop.set(key, pos == 1 ? ChargeCooling.FAN_AUTO
                                       : Integer.toString(pos == 0 ? 0 : pos - 1));
                bumpRev();
            }
        });
        row.addView(sp);
    }

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
            colIdx = clamp(idxOf((position == 3) ? concat(COL_VALS, RGB_VALS) : COL_VALS,
                                 parseInt(v.substring(6, 9), 1)), 0, colNamesLen(position) - 1);
        }

        final Spinner fx = new Spinner(this);
        fx.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, FX_NAMES));
        fx.setSelection(fxIdx);
        final boolean hasRgb = (position == 3);        // RGB presets ship for the fan ring only
        final String[] colNames = hasRgb ? concat(COL_NAMES, RGB_NAMES) : COL_NAMES;
        final int[]    colVals  = hasRgb ? concat(COL_VALS,  RGB_VALS)  : COL_VALS;
        final Spinner col = new Spinner(this);
        col.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, colNames));
        col.setSelection(colIdx);

        SimpleSel apply = new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v2, int pos, long id) {
                int fxVal = FX_VALS[fx.getSelectedItemPosition()];
                int colVal = colVals[col.getSelectedItemPosition()];
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

        final LinearLayout appRow = labelledRow(root, "App to launch");
        final Spinner app = new Spinner(this);
        app.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                labels.isEmpty() ? new String[]{"(no apps found)"} : labels.toArray(new String[0])));
        int cur = pkgs.indexOf(Prop.get(SliderWatcher.PROP_APP, ""));
        if (cur >= 0) app.setSelection(cur);
        // Same 50/50 split as the mode row: app labels can be long and would otherwise crush the
        // "App to launch" text.
        appRow.addView(app, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // "App to launch" only means anything for the two modes that launch an app; showing it
        // under "Do nothing"/"Flashlight"/"Key code" invites the user to set a value that is then
        // silently ignored. Hidden rather than dimmed so the panel stays short.
        appRow.setVisibility(usesApp(modeVals[mode.getSelectedItemPosition()])
                ? View.VISIBLE : View.GONE);

        mode.setOnItemSelectedListener(new SimpleSel() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Prop.set(SliderWatcher.PROP_MODE, String.valueOf(modeVals[pos]));
                appRow.setVisibility(usesApp(modeVals[pos]) ? View.VISIBLE : View.GONE);
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
        // Default 6h, not stock hourly: this alarm is the single largest idle waker and Doze is
        // not permitted to defer it, so leaving it hourly undoes most of what the switch above buys.
        final String[] names = {"Every 6 hours (default)", "Every hour (stock)", "Off"};
        final String[] vals  = {"6", "1", "0"};
        final String[] why = {
            "Recommended. The battery chart fills in four times a day instead of twenty-four, "
                + "and the phone stops waking on the hour to do it. On a night on the desk that is "
                + "about eight fewer wake-ups.",
            "Android's stock behaviour. The battery usage chart is accurate to the hour, at the "
                + "cost of one guaranteed wake-up every hour, all night, that Doze cannot defer.",
            "No alarm is scheduled at all. Nothing wakes the phone for the chart — but the chart "
                + "stops accumulating new history, so Battery usage will show gaps."
        };

        mBatteryStatsBox = new LinearLayout(this);
        mBatteryStatsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(mBatteryStatsBox);
        LinearLayout row = labelledRow(mBatteryStatsBox, "Battery stats refresh");
        final TextView expl = new TextView(this);
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        sp.setSelection(idxOfValue(vals, Prop.get(key, "6")));
        sp.setOnItemSelectedListener(new SimpleSel() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Prop.set(key, vals[pos]);
                expl.setText(why[pos]);
            }
        });
        row.addView(sp);

        expl.setTextSize(13);
        expl.setPadding(0, dp(4), 0, dp(10));
        expl.setText(why[idxOfValue(vals, Prop.get(key, "6"))]);
        mBatteryStatsBox.addView(expl);

        note(mBatteryStatsBox, "Settings wakes the phone every hour, on the hour, purely to refresh the battery "
                + "usage chart. It is scheduled with an alarm Doze is not allowed to defer, so the "
                + "settings above cannot touch it — measured on a real dump as 166 of 178 wake-ups.");

        // Hidden until the switch above is on; it only matters once you care about idle drain.
        final boolean dozeOn = Prop.getBool(OfflineDoze.KEY_ENABLED, OfflineDoze.DEF_ENABLED);
        android.util.Log.d("RmControl", "batteryStatsRow: doze_offline=" + dozeOn);
        setBatteryStatsVisible(dozeOn);
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
    /** The slider modes that actually consume {@link SliderWatcher#PROP_APP}. */
    private static boolean usesApp(int mode) {
        return mode == SliderWatcher.MODE_LAUNCH || mode == SliderWatcher.MODE_LAUNCH_HOME;
    }

    // ---------- VoIP call recording ----------
    // The recorder itself is the separate VoipRecorder priv-app (device/nubia/NX809J/apps): it
    // needs its own uid for the CAPTURE_VOICE_COMMUNICATION_OUTPUT audio policy and stays a
    // persistent /system app. Its two switches are Settings.Secure keys it observes; we own the
    // UI, the consent text and its runtime permissions (RECORD_AUDIO / POST_NOTIFICATIONS are
    // runtime grants nobody would otherwise ever prompt for on a launcher-less app).
    private static final String VOIPREC_PKG = "com.nx809j.voiprecorder";
    private static final String OTP_ENABLED  = "rm_otp_enabled";
    private static final String OTP_AUTOCOPY = "rm_otp_autocopy";
    private static final String OTP_NOTIFY   = "rm_otp_notify";

    private void otpSmsSection(LinearLayout root) {
        LinearLayout row = labelledRow(root, "Detect verification codes in SMS");
        final Switch master = new Switch(this);
        final boolean masterOn = Settings.Secure.getInt(getContentResolver(), OTP_ENABLED, 1) != 0;
        master.setChecked(masterOn);
        row.addView(master);

        final LinearLayout autoRow = labelledRow(root, "Auto-copy the code to the clipboard");
        final Switch auto = new Switch(this);
        auto.setChecked(secureOn(OTP_AUTOCOPY));
        auto.setOnCheckedChangeListener((v, on) ->
                Settings.Secure.putInt(getContentResolver(), OTP_AUTOCOPY, on ? 1 : 0));
        autoRow.addView(auto);

        final LinearLayout notifyRow = labelledRow(root, "Also show a RedMagic \"Copy code\" notification");
        final Switch notify = new Switch(this);
        notify.setChecked(secureOn(OTP_NOTIFY));
        notify.setOnCheckedChangeListener((v, on) ->
                Settings.Secure.putInt(getContentResolver(), OTP_NOTIFY, on ? 1 : 0));
        notifyRow.addView(notify);

        // The sub-options are meaningless with detection off; grey them out instead of letting
        // the user enable one and wonder why nothing happens.
        final Runnable sync = () -> {
            boolean on = master.isChecked();
            auto.setEnabled(on);
            autoRow.setAlpha(on ? 1f : 0.4f);
            notify.setEnabled(on);
            notifyRow.setAlpha(on ? 1f : 0.4f);
        };
        sync.run();
        master.setOnCheckedChangeListener((v, on) -> {
            Settings.Secure.putInt(getContentResolver(), OTP_ENABLED, on ? 1 : 0);
            sync.run();
        });

        note(root, "The Messages notification already offers a one-tap \"Copy code\" for texts "
                + "with a verification code, and the keyboard offers whatever was copied as a "
                + "paste chip. With detection on, Auto-copy puts the code on the clipboard the "
                + "moment it arrives so you just paste; the extra RedMagic notification is off by "
                + "default so you are not prompted twice. Codes are matched on the phone; nothing "
                + "is uploaded. There is no Google SMS autofill on this build, so codes are copied "
                + "rather than typed into the field for you.");
    }

    private static final String VOIPREC_ENABLED = "rm_voiprec_enabled";
    private static final String VOIPREC_MODE  = "rm_voiprec_mode";    // 0 listed only, 1 ask, 2 all
    private static final String VOIPREC_ALLOW = "rm_voiprec_allow";   // comma-separated packages
    private static final String VOIPREC_DENY  = "rm_voiprec_deny";

    private boolean secureOn(String key) {
        return Settings.Secure.getInt(getContentResolver(), key, 0) != 0;
    }

    private void voipRecorderSection(LinearLayout root) {
        if (!PrivacyGuard.installed(this, VOIPREC_PKG)) {
            note(root, "The VoIP call recorder is not installed in this build.");
            return;
        }
        LinearLayout row = labelledRow(root, "Auto-record VoIP calls");
        final Switch master = new Switch(this);
        master.setChecked(secureOn(VOIPREC_ENABLED));
        master.setOnCheckedChangeListener((v, on) -> {
            if (!on) {
                Settings.Secure.putInt(getContentResolver(), VOIPREC_ENABLED, 0);
                return;
            }
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Recording calls")
                    .setMessage("Records both sides of VoIP calls from WhatsApp (and, optionally, "
                            + "other calling apps) and saves them as WAV files in the "
                            + "CallRecordings folder.\n\nRecording a call may require the consent "
                            + "of everyone on the call. Laws differ by country and some places "
                            + "require all parties to agree. You are responsible for using this "
                            + "legally and for informing the other party. A notification is shown "
                            + "while a call is being recorded.\n\nOnly your mic (uplink) and the "
                            + "other party (downlink) are captured; nothing leaves the phone.")
                    .setPositiveButton("I understand", (d, w) -> {
                        grantVoipRecorderPerms();
                        Settings.Secure.putInt(getContentResolver(), VOIPREC_ENABLED, 1);
                        toast("Auto-recording enabled");
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> master.setChecked(false))
                    .setOnCancelListener(d -> master.setChecked(false))
                    .show();
        });
        row.addView(master);

        // Per-app policy: record list, ignore list, and what to do with everything else.
        row = labelledRow(root, "Apps not on either list");
        final Spinner mode = new Spinner(this);
        mode.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[] { "Don't record", "Ask me on their first call", "Record" }));
        mode.setSelection(clamp(Settings.Secure.getInt(getContentResolver(), VOIPREC_MODE, 1), 0, 2));
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Settings.Secure.putInt(getContentResolver(), VOIPREC_MODE, pos);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        row.addView(mode);

        mVoipRecordList = new LinearLayout(this);
        mVoipRecordList.setOrientation(LinearLayout.VERTICAL);
        mVoipIgnoreList = new LinearLayout(this);
        mVoipIgnoreList.setOrientation(LinearLayout.VERTICAL);
        TextView h = new TextView(this); h.setTextSize(14); h.setText("Always record");
        h.setPadding(0, dp(8), 0, 0); root.addView(h);
        root.addView(mVoipRecordList);
        Button addRec = new Button(this, null, android.R.attr.borderlessButtonStyle);
        addRec.setText("Add app to record\u2026"); addRec.setTextColor(accentColor());
        addRec.setOnClickListener(v -> showAppPicker(getPackageManager(),
                voipList(VOIPREC_ALLOW), pkg -> { voipDecide(pkg, true); rebuildVoipLists(); }));
        root.addView(addRec);
        h = new TextView(this); h.setTextSize(14); h.setText("Never record");
        h.setPadding(0, dp(8), 0, 0); root.addView(h);
        root.addView(mVoipIgnoreList);
        Button addIgn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        addIgn.setText("Add app to ignore\u2026"); addIgn.setTextColor(accentColor());
        addIgn.setOnClickListener(v -> showAppPicker(getPackageManager(),
                voipList(VOIPREC_DENY), pkg -> { voipDecide(pkg, false); rebuildVoipLists(); }));
        root.addView(addIgn);
        rebuildVoipLists();

        final Button open = new Button(this);
        open.setText("Recordings\u2026");
        open.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent().setClassName(VOIPREC_PKG,
                        VOIPREC_PKG + ".RecordingsActivity"));
            } catch (Exception e) {
                toast("Cannot open the recordings browser: " + e.getMessage());
            }
        });
        root.addView(open);
        note(root, "Nothing runs while idle: a call is detected from the app opening the "
                + "microphone in communication mode, recorded through the audio HAL's VoIP "
                + "capture path (both sides, no speakerphone needed) and saved to "
                + "/sdcard/CallRecordings as \u201cApp - Contact (date).wav\u201d; the contact "
                + "comes from the app's own call notification. With \u201cAsk me\u201d, the first "
                + "call from an app not on either list is not recorded; a notification offers "
                + "Always / This call / Never. Tap a recording to play it, long-press to delete; "
                + "the browser's menu has a 15 s test recording.");
    }

    private LinearLayout mVoipRecordList, mVoipIgnoreList;

    /** Comma-separated Secure package list; the record list defaults to WhatsApp. */
    private java.util.Set<String> voipList(String key) {
        String v = Settings.Secure.getString(getContentResolver(), key);
        if (v == null) v = VOIPREC_ALLOW.equals(key) ? "com.whatsapp,com.whatsapp.w4b" : "";
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String p : v.split(",")) { p = p.trim(); if (!p.isEmpty()) out.add(p); }
        return out;
    }

    private void voipStore(String key, java.util.Set<String> set) {
        Settings.Secure.putString(getContentResolver(), key, String.join(",", set));
    }

    /** Put pkg on the record list (record=true) or the ignore list, removing it from the other. */
    private void voipDecide(String pkg, boolean record) {
        java.util.Set<String> a = voipList(VOIPREC_ALLOW), d = voipList(VOIPREC_DENY);
        if (record) { a.add(pkg); d.remove(pkg); } else { d.add(pkg); a.remove(pkg); }
        voipStore(VOIPREC_ALLOW, a); voipStore(VOIPREC_DENY, d);
    }

    private void rebuildVoipLists() {
        rebuildVoipList(mVoipRecordList, VOIPREC_ALLOW, "No apps \u2014 nothing is recorded automatically.");
        rebuildVoipList(mVoipIgnoreList, VOIPREC_DENY, "No apps.");
    }

    private void rebuildVoipList(LinearLayout list, final String key, String emptyText) {
        if (list == null) return;
        list.removeAllViews();
        final java.util.List<String> pkgs = new java.util.ArrayList<>(voipList(key));
        if (pkgs.isEmpty()) { note(list, emptyText); return; }
        java.util.Collections.sort(pkgs, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                PrivacyGuard.label(this, a), PrivacyGuard.label(this, b)));
        for (String pkg : pkgs) {
            final boolean installed = PrivacyGuard.installed(this, pkg);
            final LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setPadding(dp(8), dp(2), 0, dp(2));
            final TextView name = new TextView(this);
            name.setTextSize(15);
            name.setText(PrivacyGuard.label(this, pkg) + (installed ? "" : "  (not installed)"));
            top.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final Button remove = new Button(this, null, android.R.attr.borderlessButtonStyle);
            remove.setText("Remove");
            remove.setTextColor(accentColor());
            remove.setOnClickListener(v -> {
                java.util.Set<String> set = voipList(key);
                set.remove(pkg);
                voipStore(key, set);
                rebuildVoipLists();
            });
            top.addView(remove);
            list.addView(top);
        }
    }

    private void grantVoipRecorderPerms() {
        final PackageManager pm = getPackageManager();
        for (String perm : new String[] { android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS }) {
            try {
                pm.grantRuntimePermission(VOIPREC_PKG, perm, android.os.Process.myUserHandle());
            } catch (Throwable t) {
                android.util.Log.w("RMControl", "grant " + perm + " to recorder failed", t);
            }
        }
    }

    // ---------- privacy guard (per-app camera / mic / location, invisible to the app) ----------
    private Button mManageGuarded;

    private void privacySection(LinearLayout root) {
        LinearLayout row = labelledRow(root, "Privacy guard");
        final Switch master = new Switch(this);
        master.setChecked(PrivacyGuard.enabled());
        master.setOnCheckedChangeListener((v, on) -> PrivacyGuard.setEnabled(getApplicationContext(), on));
        row.addView(master);
        note(root, "Blocks camera, microphone, location, contacts, photos & videos, audio & music, "
                + "files or nearby devices for the apps below WITHOUT taking the permission away, so "
                + "the app never notices: it keeps thinking it has access, the mic records silence, "
                + "the camera is \"unavailable\", location never fixes, and contacts/media/files "
                + "come back empty. No green privacy dot for camera/mic.\n\n"
                + "Camera/mic attempts still notify with \"Allow for 10 min\" for the times you want "
                + "it (a video call, a bank's ID check); the others are blocked quietly. Rules "
                + "survive reboots and app updates; also on the Privacy guard Quick Settings tile.");

        LinearLayout autoRow = labelledRow(root, "Auto-guard newly installed apps");
        final Switch auto = new Switch(this);
        auto.setChecked(PrivacyGuard.autoEnabled());
        auto.setOnCheckedChangeListener((v, on) -> {
            PrivacyGuard.setAutoEnabled(getApplicationContext(), on);
            if (on && !PrivacyGuard.enabled()) master.setChecked(true);
        });
        autoRow.addView(auto);
        note(root, "When on, every app you install from now on is added here with everything "
                + "blocked, so a new app gets nothing until you tick what it may use below.");

        mManageGuarded = new Button(this);
        mManageGuarded.setOnClickListener(v -> showGuardedAppsManager(master));
        root.addView(mManageGuarded);
        refreshManageButton();
    }

    private void refreshManageButton() {
        if (mManageGuarded == null) return;
        final int n = PrivacyGuard.rules(this).size();
        mManageGuarded.setText(n == 0 ? "Manage guarded apps\u2026"
                : "Manage guarded apps (" + n + ")\u2026");
    }

    // ---------- screenshots: per-app FLAG_SECURE override ----------
    private LinearLayout mScreenshotList;

    private void screenshotSection(LinearLayout root) {
        LinearLayout copyRow = labelledRow(root, "Copy every screenshot to the clipboard");
        final Switch autoCopy = new Switch(this);
        autoCopy.setChecked(Settings.Secure.getInt(getContentResolver(), "rm_screenshot_autocopy", 1) != 0);
        autoCopy.setOnCheckedChangeListener((v, on) ->
                Settings.Secure.putInt(getContentResolver(), "rm_screenshot_autocopy", on ? 1 : 0));
        copyRow.addView(autoCopy);
        note(root, "The screenshot is saved as usual and also put on the clipboard the moment it "
                + "is taken, so it can be pasted right away (the keyboard offers it as a paste "
                + "chip). The preview's Copy chip still works with this off.");

        note(root, "Apps listed here cannot block screenshots or screen recording: the system "
                + "ignores their \u201csecure window\u201d flag. Only these apps \u2014 everything "
                + "else (banking, payments, work profile) stays protected. The app is not told; it "
                + "still believes the flag is set. Takes effect immediately, survives reboots.");
        mScreenshotList = new LinearLayout(this);
        mScreenshotList.setOrientation(LinearLayout.VERTICAL);
        root.addView(mScreenshotList);
        rebuildScreenshotList();

        final Button add = new Button(this);
        add.setText("Add app\u2026");
        add.setOnClickListener(v -> showAppPicker(getPackageManager(),
                ScreenshotAllow.list(this), pkg -> {
                    ScreenshotAllow.add(getApplicationContext(), pkg);
                    rebuildScreenshotList();
                }));
        root.addView(add);
    }

    private void rebuildScreenshotList() {
        final LinearLayout list = mScreenshotList;
        list.removeAllViews();
        final java.util.List<String> pkgs = new java.util.ArrayList<>(ScreenshotAllow.list(this));
        if (pkgs.isEmpty()) {
            note(list, "No apps yet \u2014 e.g. add WhatsApp or Telegram.");
            return;
        }
        java.util.Collections.sort(pkgs, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                PrivacyGuard.label(this, a), PrivacyGuard.label(this, b)));
        for (String pkg : pkgs) {
            final boolean installed = PrivacyGuard.installed(this, pkg);
            final LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setPadding(0, dp(4), 0, dp(4));
            final TextView name = new TextView(this);
            name.setTextSize(15);
            name.setText(PrivacyGuard.label(this, pkg) + (installed ? "" : "  (not installed)"));
            top.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final Button remove = new Button(this, null, android.R.attr.borderlessButtonStyle);
            remove.setText("Remove");
            remove.setTextColor(accentColor());
            remove.setOnClickListener(v -> {
                ScreenshotAllow.remove(getApplicationContext(), pkg);
                rebuildScreenshotList();
            });
            top.addView(remove);
            list.addView(top);
        }
    }

    // ---- Guarded-apps manager: a searchable floating list; tap an app to edit its blocks ----
    private void showGuardedAppsManager(final Switch master) {
        final int pad = dp(16);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, 0);

        final android.widget.EditText search = new android.widget.EditText(this);
        search.setHint("Search guarded apps");
        search.setSingleLine(true);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(search);

        final java.util.List<String> rows = new java.util.ArrayList<>();
        final java.util.Map<String, String> pkgByRow = new java.util.HashMap<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1,
                new java.util.ArrayList<String>()) {
            @Override public android.view.View getView(int position, android.view.View convertView,
                    android.view.ViewGroup parent) {
                final android.view.View v = super.getView(position, convertView, parent);
                final String pkg = pkgByRow.get(getItem(position));
                ((TextView) v.findViewById(android.R.id.text1)).setText(
                        PrivacyGuard.label(SettingsActivity.this, pkg)
                        + (PrivacyGuard.installed(SettingsActivity.this, pkg) ? "" : "  (not installed)"));
                ((TextView) v.findViewById(android.R.id.text2)).setText(blockedSummary(pkg));
                return v;
            }
        };
        final android.widget.ListView lv = new android.widget.ListView(this);
        lv.setAdapter(adapter);
        box.addView(lv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420)));

        final TextView empty = new TextView(this);
        empty.setPadding(0, dp(12), 0, dp(12));
        empty.setTextColor(0xFF888888);
        box.addView(empty);

        final Runnable reload = () -> {
            rows.clear(); pkgByRow.clear();
            final java.util.List<java.util.Map.Entry<String, Integer>> sorted =
                    new java.util.ArrayList<>(PrivacyGuard.rules(this).entrySet());
            java.util.Collections.sort(sorted, (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                    PrivacyGuard.label(this, a.getKey()), PrivacyGuard.label(this, b.getKey())));
            for (java.util.Map.Entry<String, Integer> e : sorted) {
                final String key = PrivacyGuard.label(this, e.getKey()) + "\n" + e.getKey();
                rows.add(key); pkgByRow.put(key, e.getKey());
            }
            empty.setText(rows.isEmpty()
                    ? "No apps guarded yet. Use \u201cAdd app\u2026\u201d below." : "");
            applyPrivacyFilter(search, rows, adapter);
            refreshManageButton();
        };
        reload.run();

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            public void onTextChanged(CharSequence c, int a, int b, int d) {}
            public void afterTextChanged(android.text.Editable ed) {
                applyPrivacyFilter(search, rows, adapter);
            }
        });

        lv.setOnItemClickListener((p, v, pos, id) -> {
            final String pkg = pkgByRow.get(adapter.getItem(pos));
            if (pkg != null) showPrivacyAppEditor(pkg, reload);
        });

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Guarded apps")
                .setView(box)
                .setPositiveButton("Add app\u2026", null)   // click handler set below so it stays open
                .setNegativeButton("Close", null)
                .show();
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                showAppPicker(getPackageManager(), PrivacyGuard.rules(this).keySet(), pkg -> {
                    PrivacyGuard.setMask(getApplicationContext(), pkg, PrivacyGuard.ALL);
                    if (master != null && !PrivacyGuard.enabled()) master.setChecked(true);
                    reload.run();
                    showPrivacyAppEditor(pkg, reload);
                }));
    }

    private void applyPrivacyFilter(android.widget.EditText search, java.util.List<String> rows,
            ArrayAdapter<String> adapter) {
        final String q = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        adapter.setNotifyOnChange(false);
        adapter.clear();
        for (String row : rows) {
            if (q.isEmpty() || row.toLowerCase(java.util.Locale.ROOT).contains(q)) adapter.add(row);
        }
        adapter.notifyDataSetChanged();
    }

    private String blockedSummary(String pkg) {
        final int mask = PrivacyGuard.maskOf(this, pkg);
        if (mask == 0) return "Nothing blocked";
        final long until = PrivacyGuard.allowedUntil(this, pkg);
        final String suffix = until == 0 ? "" : "  \u2014 allowed until "
                + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(until));
        if (mask == PrivacyGuard.ALL) return "Everything blocked" + suffix;
        final int[] bits = { PrivacyGuard.CAMERA, PrivacyGuard.MIC, PrivacyGuard.LOCATION,
                PrivacyGuard.CONTACTS, PrivacyGuard.MEDIA_VISUAL, PrivacyGuard.MEDIA_AUDIO,
                PrivacyGuard.FILES, PrivacyGuard.NEARBY };
        final String[] names = { "Camera", "Mic", "Location", "Contacts", "Photos", "Audio",
                "Files", "Nearby" };
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bits.length; i++) {
            if ((mask & bits[i]) != 0) { if (sb.length() > 0) sb.append(", "); sb.append(names[i]); }
        }
        return sb.toString() + suffix;
    }

    // ---- Per-app editor: the eight category toggles + mute + allow status + remove ----
    private void showPrivacyAppEditor(final String pkg, final Runnable onChange) {
        final int pad = dp(16);
        final ScrollView sv = new ScrollView(this);
        final LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(pad, dp(8), pad, dp(8));
        sv.addView(block);

        final TextView sub = new TextView(this);
        sub.setTextSize(12);
        sub.setTextColor(0xFF888888);
        sub.setText(pkg + (PrivacyGuard.installed(this, pkg) ? "" : "  (not installed)"));
        block.addView(sub);

        final int[] bits = { PrivacyGuard.CAMERA, PrivacyGuard.MIC, PrivacyGuard.LOCATION,
                PrivacyGuard.CONTACTS, PrivacyGuard.MEDIA_VISUAL, PrivacyGuard.MEDIA_AUDIO,
                PrivacyGuard.FILES, PrivacyGuard.NEARBY };
        final String[] names = { "Camera", "Mic", "Location", "Contacts", "Photos & videos",
                "Audio & music", "Files", "Nearby devices" };
        for (int i = 0; i < bits.length; i++) {
            final int bit = bits[i];
            final android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(names[i]);
            cb.setChecked((PrivacyGuard.maskOf(this, pkg) & bit) != 0);
            cb.setOnCheckedChangeListener((v, on) -> {
                final int cur = PrivacyGuard.maskOf(this, pkg);
                PrivacyGuard.setMask(getApplicationContext(), pkg, on ? (cur | bit) : (cur & ~bit));
                if (onChange != null) onChange.run();
            });
            block.addView(cb);
        }

        final android.widget.CheckBox mute = new android.widget.CheckBox(this);
        mute.setText("Mute \u201cblocked\u201d alerts for this app");
        mute.setChecked(PrivacyGuard.muted(this, pkg));
        mute.setOnCheckedChangeListener((v, on) ->
                PrivacyGuard.setMuted(getApplicationContext(), pkg, on));
        block.addView(mute);

        // Android Auto: only meaningful when mic or location is guarded for this app.
        if ((PrivacyGuard.maskOf(this, pkg) & PrivacyGuard.AA_EXEMPT) != 0) {
            final android.widget.CheckBox aa = new android.widget.CheckBox(this);
            aa.setText("Allow mic & location while Android Auto is connected");
            aa.setChecked(PrivacyGuard.aaAllowed(this, pkg));
            aa.setOnCheckedChangeListener((v, on) ->
                    PrivacyGuard.setAaAllowed(getApplicationContext(), pkg, on));
            block.addView(aa);
        }

        final long until = PrivacyGuard.allowedUntil(this, pkg);
        if (until != 0) {
            final TextView tmp = new TextView(this);
            tmp.setTextSize(12);
            tmp.setTextColor(accentColor());
            tmp.setText("Allowed until " + android.text.format.DateFormat.getTimeFormat(this)
                    .format(new java.util.Date(until)) + " \u2014 tap to guard again now");
            tmp.setOnClickListener(v -> {
                PrivacyGuard.revokeAllow(getApplicationContext(), pkg);
                tmp.setVisibility(android.view.View.GONE);
                if (onChange != null) onChange.run();
            });
            block.addView(tmp);
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle(PrivacyGuard.label(this, pkg))
                .setView(sv)
                .setPositiveButton("Done", (d, w) -> { if (onChange != null) onChange.run(); })
                .setNeutralButton("Remove", (d, w) -> {
                    PrivacyGuard.setMask(getApplicationContext(), pkg, 0);
                    if (onChange != null) onChange.run();
                })
                .show();
    }

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

    private void chargeCoolSwitch(LinearLayout root, String title) {
        LinearLayout row = labelledRow(root, title);
        Switch sw = new Switch(this);
        sw.setChecked(Prop.getBool(ChargeCooling.PROP_ENABLED, true));
        sw.setOnCheckedChangeListener((v, on) -> {
            Prop.set(ChargeCooling.PROP_ENABLED, on ? "1" : "0");
            ChargeCooling.reevaluate(getApplicationContext());
            bumpRev();
        });
        row.addView(sw);
    }

    private void rmSwitch(LinearLayout root, String title, final String key, boolean def) {
        LinearLayout row = labelledRow(root, title);
        Switch sw = new Switch(this);
        sw.setChecked(Prop.getBool(key, def));
        sw.setOnCheckedChangeListener((v, on) -> { Prop.set(key, on ? "1" : "0"); bumpRev(); });
        row.addView(sw);
    }

    private void addSwitch(LinearLayout root, String title, final String key) {
        addSwitch(root, title, key, false);
    }

    private void addSwitch(LinearLayout root, String title, final String key, boolean def) {
        LinearLayout row = labelledRow(root, title);
        Switch sw = new Switch(this);
        sw.setChecked(Prop.getBool(key, def));
        sw.setOnCheckedChangeListener((v, on) -> Prop.set(key, on ? "1" : "0"));
        row.addView(sw);
    }

    private void loudnessGain(LinearLayout root) {
        final String key = "persist.sys.loudness_gain_mb";
        final TextView label = new TextView(this);
        label.setTextSize(13);
        int cur = parseInt(Prop.get(key, "1000"), 1000);
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
    /** Container revealed only once "Sleep harder when offline" is on. */
    private LinearLayout mBatteryStatsBox;

    private void dozeOffline(LinearLayout root) {
        LinearLayout row = labelledRow(root, "Sleep harder when offline");
        Switch sw = new Switch(this);
        sw.setChecked(Prop.getBool(OfflineDoze.KEY_ENABLED, OfflineDoze.DEF_ENABLED));
        sw.setOnCheckedChangeListener((v, on) -> {
            Prop.set(OfflineDoze.KEY_ENABLED, on ? "1" : "0");
            OfflineDoze.reapply(this);
            setBatteryStatsVisible(on);
        });
        row.addView(sw);
    }

    private void setBatteryStatsVisible(boolean visible) {
        // One container, not a list of loose children: hiding several siblings individually
        // left the row on screen on-device even though the compiled call passed false
        // (verified in smali). A single parent with its own visibility is unambiguous, and
        // it correctly takes the explanatory note with it.
        if (mBatteryStatsBox != null) {
            mBatteryStatsBox.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
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
