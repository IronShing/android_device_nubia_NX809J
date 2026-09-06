package com.nubia.rmcontrol;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * External-display mode policy: two persistent CAPS ("no more than 1080p", "no more than 60 Hz")
 * that are re-applied automatically every time a screen is connected.
 *
 * WHY CAPS AND NOT A MODE PICKER (XDA feedback, NX123Dos, 2026-08-31):
 * the previous UI listed the connected sink's modes and pinned one. That is inherently
 * per-display, and every connect is a new display id, so the choice never stuck -- "Effort to
 * preserve setting to specific device fail immediately or shortly". A hub that cannot carry more
 * than 1080p60 is a property of the CABLE, not of the screen, so the limit belongs to the phone
 * and should be set once: "set limits and forget".
 *
 * HOW IT WORKS. /sys/kernel/lcd_enhance/edid_modes is nubia's mirror of QTI's DP mode-override
 * (msm/nubiadp/nubia_dp_preference.c). Writing "H V Hz aspect" sets dp_panel->mode_override, and
 * dp_connector_mode_valid() then returns MODE_BAD for every mode that is not that one. Writing
 * "0 0 0 0" clears it.
 *
 * TWO HARDWARE CONSTRAINTS shape everything here, both verified 2026-08-30:
 *  1. The override must name a mode the sink ACTUALLY ADVERTISED, aspect included. An
 *     unsatisfiable override makes dp_connector_mode_valid() reject everything except the
 *     640x480 failsafe and the screen collapses to that. So a cap can only ever be resolved
 *     against a real readback -- which is why this runs on connect and not at boot, and why
 *     {@link #pick} returns null (= clear the override) rather than inventing a timing.
 *  2. The override is consulted when the driver PROBES the sink, so writing it while a display
 *     is already up does not change the running mode. That is what {@link #reprobe} is for.
 */
final class ExternalDisplay {

    private static final String TAG = "RMControl";

    /** nubia's mirror of the QTI DP mode-override. */
    static final String NODE_EDID_MODES = "/sys/kernel/lcd_enhance/edid_modes";
    /**
     * Simulated hotplug. Write-only in practice: reads return EIO even with nothing connected
     * (verified 2026-08-31). Writing 0 then 1 is a virtual unplug/replug, which is the only way
     * to make a freshly written override affect an ALREADY-CONNECTED screen.
     */
    static final String NODE_HPD = "/sys/kernel/lcd_enhance/hpd";

    /** Cap on vertical resolution: "auto" | "480" | "720" | "1080". */
    static final String PROP_MAX_RES = "persist.sys.rm.dp.max_res";
    /** Cap on refresh rate: "auto" | "30" | "60" | "90". */
    static final String PROP_MAX_HZ = "persist.sys.rm.dp.max_hz";
    /**
     * Kill switch for the automatic re-probe. Default on -- without it the caps only take effect
     * on the NEXT connection, which is the complaint this feature exists to fix. Set to 0 if the
     * simulated hotplug misbehaves on some adapter.
     */
    static final String PROP_AUTO_REPROBE = "persist.sys.rm.dp.autoreprobe";
    /**
     * UI size on external screens: "auto" | dpi AT 1080p ("120".."320"). Scaled by the screen's
     * shorter side (1440p x1.33, 4K x2) so one choice fits every monitor, then forced on each
     * external display through WindowManager. Auto = the ROM default
     * (config_externalDisplayBaseDensityAt1080p, 160 at 1080p); choosing it clears the force
     * once, after which Settings' per-monitor "Display size" slider is left alone.
     */
    static final String PROP_DENSITY = "persist.sys.rm.dp.density";
    /** Must match the device overlay's config_externalDisplayBaseDensityAt1080p. */
    static final int DEFAULT_DENSITY_1080P = 160;

    /** One line of the edid_modes readback: "1920x1080 60 2" = WxH REFRESH ASPECT. */
    static final class Mode {
        final int w, h, rate, aspect;

        Mode(int w, int h, int rate, int aspect) {
            this.w = w; this.h = h; this.rate = rate; this.aspect = aspect;
        }

        String label() { return w + "x" + h + "  " + rate + " Hz"; }

        /** The exact wire format dp_debug_write_edid_modes() parses. */
        String wire() { return w + " " + h + " " + rate + " " + aspect; }
    }

    /**
     * The sink's advertised modes.
     *
     * ⚠ VERIFIED 2026-08-31: this node reads back EMPTY unless a mode override is already set --
     * nubia's show() only prints the ladder while mode_override is active. A plain read on a
     * freshly connected screen therefore returns nothing, which is indistinguishable from "no
     * display". {@link #primedModes} works around it. Use that, not this, from the connect path.
     */
    static List<Mode> readModes() {
        final List<Mode> out = new ArrayList<>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(NODE_EDID_MODES));
            String line;
            while ((line = r.readLine()) != null) {
                final String[] tok = line.trim().split("\\s+");
                if (tok.length < 3) continue;
                final int x = tok[0].indexOf('x');
                if (x <= 0) continue;
                try {
                    out.add(new Mode(
                            Integer.parseInt(tok[0].substring(0, x)),
                            Integer.parseInt(tok[0].substring(x + 1)),
                            Integer.parseInt(tok[1]),
                            Integer.parseInt(tok[2])));
                } catch (NumberFormatException ignored) { /* skip junk line */ }
            }
        } catch (Throwable t) {
            Log.w(TAG, "edid_modes unreadable", t);
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /** De-duplicated by w/h/rate, largest area first then fastest. */
    static List<Mode> distinctSorted(List<Mode> all) {
        final List<Mode> pick = new ArrayList<>();
        for (Mode m : all) {
            boolean dup = false;
            for (Mode q : pick) if (q.w == m.w && q.h == m.h && q.rate == m.rate) { dup = true; break; }
            if (!dup) pick.add(m);   // keeps the first aspect seen for this w/h/rate
        }
        // Resolution is the lever that reduces link load: measured 2026-08-30, 720p60 = 74.25 MHz
        // vs 148.5 MHz for BOTH 1080p60 and 1080p50, because the 50 Hz timing just has wider
        // blanking. So rank by pixels first, rate second.
        Collections.sort(pick, (a, b) -> {
            if (a.w * a.h != b.w * b.h) return b.w * b.h - a.w * a.h;
            return b.rate - a.rate;
        });
        return pick;
    }

    private static int capOf(String prop) {
        final String v = Prop.get(prop, "auto");
        if (v == null || v.isEmpty() || "auto".equals(v)) return 0;   // 0 == no limit
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return 0; }
    }

    static int maxRes() { return capOf(PROP_MAX_RES); }

    /** Chosen dpi at 1080p, 0 = auto. */
    static int density1080p() { return capOf(PROP_DENSITY); }

    /** What a 1080p choice becomes on a screen with this shorter side (int math, as the framework). */
    static int scaledDensity(int dpiAt1080p, int shortSide) {
        return Math.max(dpiAt1080p * Math.max(shortSide, 1) / 1080, 100);
    }

    /**
     * Force (or clear) the chosen UI size on external displays. Absolute dpi rather than
     * WindowManager's ratio API on purpose: this runs again after every settle, including the one
     * our own re-probe causes, so a resolution cap changes the value here rather than relying on
     * the framework to keep a ratio.
     *
     * Two knobs write the same framework value: this spinner (a default for ANY screen) and
     * Settings' per-monitor "Display size" slider. The slider must win for its monitor, or it
     * "reverts as soon as I press Back" (user report 2026-09-05: Back from the Settings page
     * lands in our onResume, which re-forced the spinner value). Rule: a screen is forced only
     * when it has no override at all (base == initial dpi) or when the override present is the
     * one WE wrote last time (recorded per monitor in {@link #DENSITY_PREFS}); anything else is
     * the slider's choice and is left alone -- including "Default", which clears the override
     * and therefore reads as "not ours" while our record says we forced something.
     *
     * @param fromUser the spinner was just changed: apply it to the connected screens regardless
     *                 of any per-monitor value, and with Auto drop any force present. Off for the
     *                 settle/resume paths, where Auto touches nothing.
     * @return the dpi forced on the first external display, 0 if none / auto.
     */
    static int applyDensity(Context ctx, boolean fromUser) {
        final DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        if (dm == null) return 0;
        final int want = density1080p();
        if (want == 0 && !fromUser) return 0;
        final android.view.IWindowManager wm = android.view.WindowManagerGlobal.getWindowManagerService();
        if (wm == null) return 0;
        final int user = android.os.UserHandle.myUserId();
        final android.content.SharedPreferences ours =
                ctx.getSharedPreferences(DENSITY_PREFS, Context.MODE_PRIVATE);
        int first = 0;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY || d.getType() != Display.TYPE_EXTERNAL) continue;
            final int id = d.getDisplayId();
            final Display.Mode m = d.getMode();
            final int target = want == 0 ? 0
                    : scaledDensity(want, Math.min(m.getPhysicalWidth(), m.getPhysicalHeight()));
            final String key = d.getUniqueId() != null ? d.getUniqueId() : "id:" + id;
            try {
                final int base = wm.getBaseDisplayDensity(id);
                final int mine = ours.getInt(key, 0);
                if (!fromUser) {
                    final boolean untouched = base == wm.getInitialDisplayDensity(id) && mine == 0;
                    if (!untouched && base != mine) {
                        Log.i(TAG, "display " + id + " dpi " + base + " set in Settings; leaving it");
                        continue;
                    }
                }
                if (target == 0) {
                    wm.clearForcedDisplayDensityForUser(id, user);
                    ours.edit().remove(key).apply();
                } else {
                    if (base != target) wm.setForcedDisplayDensityForUser(id, target, user);
                    ours.edit().putInt(key, target).apply();
                }
                if (first == 0) first = target;
            } catch (android.os.RemoteException | RuntimeException e) {
                Log.w(TAG, "density on display " + id + ": " + e);
            }
        }
        return first;
    }

    /** uniqueId -> dpi this app last forced on that monitor (see {@link #applyDensity}). */
    private static final String DENSITY_PREFS = "dp_density";

    static int maxHz() { return capOf(PROP_MAX_HZ); }

    /**
     * An exact resolution to prefer, "1920x1080", chosen from what a scanned screen advertised
     * (DeX-style picker, user request 2026-09-05). It is a PREFERENCE layered on the caps, not a
     * replacement for them: if the connected screen does not advertise it (different monitor,
     * or a hub that hides modes) the caps above decide as before, so the choice can never
     * strand a screen -- constraint 1 in the class comment still holds because we only ever
     * write a mode taken from the readback.
     */
    static final String PROP_RES = "persist.sys.rm.dp.res";

    /** {w, h} or null for Auto. */
    static int[] preferredRes() {
        final String v = Prop.get(PROP_RES, "auto");
        if (v == null || v.isEmpty() || "auto".equals(v)) return null;
        final int x = v.indexOf('x');
        if (x <= 0) return null;
        try {
            final int w = Integer.parseInt(v.substring(0, x).trim());
            final int h = Integer.parseInt(v.substring(x + 1).trim());
            return (w > 0 && h > 0) ? new int[] { w, h } : null;
        } catch (NumberFormatException e) { return null; }
    }

    /** Does the ladder advertise the preferred resolution at any rate? */
    static boolean advertises(List<Mode> ladder, int[] res) {
        if (res == null) return false;
        for (Mode m : ladder) if (m.w == res[0] && m.h == res[1]) return true;
        return false;
    }

    /** "16:9", "16:10", "21:9"... for the chooser labels; falls back to the reduced ratio. */
    static String aspectLabel(int w, int h) {
        if (w <= 0 || h <= 0) return "";
        final double r = (double) w / h;
        final String[] names = { "16:9", "16:10", "4:3", "5:4", "21:9", "3:2", "32:9", "1:1" };
        final double[] vals = { 16 / 9.0, 1.6, 4 / 3.0, 1.25, 21 / 9.0, 1.5, 32 / 9.0, 1.0 };
        for (int i = 0; i < names.length; i++) if (Math.abs(r - vals[i]) < 0.02) return names[i];
        int a = w, b = h;
        while (b != 0) { final int t = a % b; a = b; b = t; }
        return (w / a) + ":" + (h / a);
    }

    /**
     * Best mode within the caps, or null meaning "clear the override / let the sink decide".
     *
     * Null is returned both when no cap is set and when nothing satisfies the caps. The second
     * case is deliberate and is the safety net for constraint 1 above: capping to 1080p60 on a
     * sink with no 1080p60 timing must give the user their screen at whatever it wants, not a
     * black screen or the 640x480 failsafe.
     */
    static Mode pick(List<Mode> modes) {
        final List<Mode> ladder = distinctSorted(modes);
        if (ladder.isEmpty()) return null;
        final int capH = maxRes();
        final int capHz = maxHz();
        final int[] pref = preferredRes();
        if (advertises(ladder, pref)) {
            // Exact resolution wins; the Hz cap still trims the rate. If no rate of it fits the
            // cap, take its slowest advertised rate (ladder is fastest-first) -- the user asked
            // for this resolution explicitly, and slowest is the closest to the cable limit.
            Mode slowest = null;
            for (Mode m : ladder) {
                if (m.w != pref[0] || m.h != pref[1]) continue;
                if (capHz == 0 || m.rate <= capHz) return m;
                slowest = m;
            }
            return slowest;
        }
        for (Mode m : ladder) {
            if (capH != 0 && m.h > capH) continue;
            if (capHz != 0 && m.rate > capHz) continue;
            return m;                                  // ladder is best-first
        }
        // Caps set but nothing satisfies them. Fall back to the sink's best mode rather than
        // clearing: verified 2026-08-31 that clearing the override and re-probing drops this
        // C27F390 to the 640x480 DRM failsafe instead of auto-selecting native. Never leave the
        // user staring at 640x480 because their cap was unreachable.
        return ladder.get(0);
    }

    /**
     * Read the ladder, priming the node first if it comes back empty.
     *
     * Priming = writing the mode the sink is ALREADY running (taken from DisplayManager, which
     * exposes only a handful of modes but does know the current one). That is by definition a
     * mode the sink supports, so it cannot collapse the display; once any override is set the
     * node starts listing the full ladder (31 modes on the test monitor vs the 3 the framework
     * reports). Aspect is not exposed by the framework, so try 0 then 2.
     */
    static List<Mode> primedModes(Context ctx) {
        List<Mode> m = readModes();
        if (!m.isEmpty()) return m;

        final Mode cur = currentExternalMode(ctx);
        if (cur == null) return m;                       // genuinely nothing connected
        for (int aspect : new int[] { 0, 2 }) {
            write(cur.w + " " + cur.h + " " + cur.rate + " " + aspect);
            m = readModes();
            if (!m.isEmpty()) {
                Log.i(TAG, "edid_modes primed with aspect " + aspect + ", " + m.size() + " modes");
                return m;
            }
        }
        Log.w(TAG, "could not prime edid_modes; caps cannot be resolved for this sink");
        return m;
    }

    /** The external display's current mode, or null if no external display is attached. */
    static Mode currentExternalMode(Context ctx) {
        final DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        if (dm == null) return null;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            final Display.Mode mode = d.getMode();
            if (mode == null) continue;
            final int w = mode.getPhysicalWidth(), h = mode.getPhysicalHeight();
            if (w <= 0 || h <= 0) continue;
            return new Mode(w, h, Math.round(mode.getRefreshRate()), 0);
        }
        return null;
    }

    static boolean write(String value) {
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(NODE_EDID_MODES);
            os.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Log.i(TAG, "edid_modes <- \"" + value + "\"");
            return true;
        } catch (java.io.FileNotFoundException e) {
            // Either a build without nubia's display driver, or the system_app sepolicy rule for
            // sysfs_usb_ctrl is missing from the image. Both are invisible otherwise.
            Log.e(TAG, "edid_modes missing/denied: " + NODE_EDID_MODES, e);
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "edid_modes write failed", t);
            return false;
        } finally {
            if (os != null) try { os.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Simulated unplug/replug so a just-written override takes effect on the screen that is
     * already connected. Best-effort: a failure here only costs the automatic application, and
     * the override still applies the next time the cable is touched.
     */
    static void reprobe() {
        try (FileOutputStream os = new FileOutputStream(NODE_HPD)) {
            os.write("0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.flush();
        } catch (Throwable t) {
            Log.w(TAG, "hpd disconnect failed", t);
            return;
        }
        // Give the driver a moment to tear the link down before asking for it back.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try (FileOutputStream os = new FileOutputStream(NODE_HPD)) {
                os.write("1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
            } catch (Throwable t) {
                Log.w(TAG, "hpd connect failed", t);
            }
        }, 400);
    }

    /** What {@link #apply} did, so the UI can say something truthful. */
    static final class Result {
        final boolean connected;   // a sink was readable
        final Mode applied;        // null = override cleared (Auto, or nothing fit the caps)
        final boolean changed;     // the node actually needed writing
        final boolean fits;        // false = caps set but no advertised mode satisfies them
        final boolean prefMissing; // a preferred resolution is set but this sink lacks it
        final List<Mode> ladder;   // what the sink advertised, best first (empty if none)

        Result(boolean connected, Mode applied, boolean changed, boolean fits,
                boolean prefMissing, List<Mode> ladder) {
            this.connected = connected; this.applied = applied;
            this.changed = changed; this.fits = fits; this.prefMissing = prefMissing;
            this.ladder = ladder;
        }
    }

    /**
     * Resolve the caps against whatever is connected and write the override.
     *
     * @param allowReprobe force the change onto the running connection (see {@link #reprobe}).
     *                     Only ever done when something actually changed, to keep simulated
     *                     hotplugs rare.
     */
    static Result apply(Context ctx, boolean allowReprobe) {
        final List<Mode> modes = primedModes(ctx);
        if (modes.isEmpty()) return new Result(false, null, false, true, false, modes);
        final List<Mode> ladder = distinctSorted(modes);
        rememberSink(ctx, ladder);

        final Mode want = pick(modes);
        if (want == null) return new Result(true, null, false, true, false, ladder);

        // Did the choice actually get honoured, or did pick() fall back to native?
        final int capH = maxRes(), capHz = maxHz();
        final int[] pref = preferredRes();
        final boolean prefMissing = pref != null && !advertises(ladder, pref);
        final boolean fits = prefMissing || pref == null
                ? (capH == 0 || want.h <= capH) && (capHz == 0 || want.rate <= capHz)
                : want.w == pref[0] && want.h == pref[1];

        final String wire = want.wire();
        final String last = Prop.get(PROP_LAST_WIRE, "");
        final boolean changed = !wire.equals(last);

        if (changed && write(wire)) {
            Prop.set(PROP_LAST_WIRE, wire);
            if (allowReprobe && Prop.getBool(PROP_AUTO_REPROBE, true)) reprobe();
        }
        // The re-probe (if any) re-adds the display and lands back here with the final mode, so
        // the density seen by the user is always computed against the mode actually running.
        applyDensity(ctx, false);
        return new Result(true, want, changed, fits, prefMissing, ladder);
    }

    /**
     * Heights and refresh rates the last scanned sink offered, "2160,1440,1080,720;144,120,60,30",
     * so the Settings choosers can list what a real screen of yours can do rather than a fixed
     * safe subset -- and keep listing it after the cable is out (XDA #67, NX123Dos: "after
     * successful device scanning, populate list with all possible display mode values").
     */
    static final String PROP_SEEN = "persist.sys.rm.dp.seen";

    private static void rememberSink(Context ctx, List<Mode> ladder) {
        final List<Integer> hs = new ArrayList<>(), rs = new ArrayList<>();
        final List<String> res = new ArrayList<>();
        for (Mode m : ladder) {                          // ladder = largest area first
            if (!hs.contains(m.h)) hs.add(m.h);
            if (!rs.contains(m.rate)) rs.add(m.rate);
            final String wh = m.w + "x" + m.h;
            if (!res.contains(wh)) res.add(wh);
        }
        Collections.sort(hs, Collections.reverseOrder());
        Collections.sort(rs, Collections.reverseOrder());
        final String v = join(hs) + ";" + join(rs);
        if (!v.equals(Prop.get(PROP_SEEN, ""))) Prop.set(PROP_SEEN, v);
        // The exact W x H list is ~110 chars on a plain 1080p monitor -- over the 91-byte
        // system-property cap (2026-09-05: appending it to PROP_SEEN made the whole set()
        // throw and the Resolution list stayed empty). Kept in app storage instead.
        try {
            final java.io.File f = new java.io.File(ctx.getFilesDir(), SEEN_RES_FILE);
            final String cur = String.join(",", res);
            if (!cur.equals(readSmall(f))) {
                final FileOutputStream os = new FileOutputStream(f);
                os.write(cur.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.close();
            }
        } catch (Throwable t) { Log.w(TAG, "seen-resolutions save failed", t); }
    }

    private static final String SEEN_RES_FILE = "dp_seen_res";

    private static String readSmall(java.io.File f) {
        if (!f.isFile()) return "";
        try (java.io.FileInputStream is = new java.io.FileInputStream(f)) {
            final byte[] b = new byte[4096];
            final int n = is.read(b);
            return n > 0 ? new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim() : "";
        } catch (Throwable t) { return ""; }
    }

    private static String join(List<Integer> l) {
        final StringBuilder sb = new StringBuilder();
        for (int i : l) { if (sb.length() > 0) sb.append(','); sb.append(i); }
        return sb.toString();
    }

    /** Exact "WxH" strings of the last scanned sink, largest first; empty if none scanned. */
    static List<String> seenResolutions(Context ctx) {
        final List<String> out = new ArrayList<>();
        final String v = readSmall(new java.io.File(ctx.getFilesDir(), SEEN_RES_FILE));
        if (v.isEmpty()) return out;
        for (String t : v.split(",")) {
            t = t.trim();
            if (t.indexOf('x') > 0 && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** [0] = heights, [1] = rates, each descending; empty arrays if no sink was ever scanned. */
    static int[][] seenSink() {
        final String v = Prop.get(PROP_SEEN, "");
        final int[][] out = { new int[0], new int[0] };
        if (v == null || v.indexOf(';') < 0) return out;
        final String[] halves = v.split(";", -1);
        for (int k = 0; k < 2; k++) {
            final List<Integer> l = new ArrayList<>();
            for (String t : halves[k].split(",")) {
                try { if (!t.isEmpty()) l.add(Integer.parseInt(t.trim())); }
                catch (NumberFormatException ignored) {}
            }
            out[k] = new int[l.size()];
            for (int i = 0; i < l.size(); i++) out[k][i] = l.get(i);
        }
        return out;
    }

    /**
     * Last wire string we wrote, so a reconnect that resolves to the same mode does not trigger a
     * pointless simulated hotplug. Not the source of truth for anything else: nubia's
     * store_user_edid_modes persists a per-monitor choice inside the driver, so the panel can come
     * up on a stored mode that this property knows nothing about (observed 2026-08-30).
     */
    static final String PROP_LAST_WIRE = "persist.sys.rm.dp.last";

    /**
     * "Screen black? Fix" -- the mode-change kick, and the switch that runs it on every connect.
     *
     * 2026-09-05, own hub (USB-C dock with a DP-to-HDMI converter, Samsung C27F390): after a
     * replug the phone brings the link up exactly as on a working connection (2 lanes HBR2,
     * 1080p60, training passes, stream on, frame CRC live, HWC/SF composing real content -- a
     * screencap of the physical display shows the desktop) and the monitor lights up BLACK.
     * Proven live on the black screen, in this order: hotplug re-toggle at 1080p -> still black;
     * 640x480 -> picture; 640x480 on the same full-speed link -> picture (so not the link rate);
     * 1080p60 again -> picture. The converter keeps its HDMI output configured for the timing it
     * was last showing and only re-initialises it when the incoming timing CHANGES; the phone
     * cannot see this (sink CRC is not supported by the hub), so it cannot be detected, only
     * cured. The cure is exactly that mode change: bring the screen up at its smallest advertised
     * mode, then let the normal cap logic put the configured mode back.
     *
     * Mechanics: the kick writes the small mode and sets {@link #PROP_LAST_WIRE} to it, so the
     * re-add caused by its own re-probe lands in {@link #apply} with changed == true and the
     * normal path restores the real mode and re-probes once more. {@link #sKickedAt} stops that
     * final re-add from kicking again when {@link #PROP_AUTO_KICK} is on.
     */
    static final String PROP_AUTO_KICK = "persist.sys.rm.dp.autokick";
    /** Uptime of the last kick; a connect within this window of it is our own re-add. */
    private static long sKickedAt;
    private static final long KICK_QUIET_MS = 20000;

    /** @return false if no sink is connected or it offers only one mode (nothing to change to). */
    static boolean kick(Context ctx) {
        final List<Mode> ladder = distinctSorted(primedModes(ctx));
        if (ladder.size() < 2) return false;
        // Smallest advertised mode (ladder = largest area first); if that is what is already
        // running (a 480p cap), the largest is as good a change as any.
        Mode small = ladder.get(ladder.size() - 1);
        if (small.wire().equals(Prop.get(PROP_LAST_WIRE, ""))) small = ladder.get(0);
        final String wire = small.wire();
        if (!write(wire)) return false;
        Prop.set(PROP_LAST_WIRE, wire);
        sKickedAt = android.os.SystemClock.uptimeMillis();
        Log.i(TAG, "kick: " + small.label() + " then back to the configured mode");
        reprobe();
        return true;
    }

    private static boolean autoKickDue() {
        return Prop.getBool(PROP_AUTO_KICK, false)
                && android.os.SystemClock.uptimeMillis() - sKickedAt > KICK_QUIET_MS;
    }

    private static DisplayManager sDm;
    private static DisplayManager.DisplayListener sListener;
    private static Runnable sPending;
    private static int sRetriesLeft;

    /**
     * How long the display topology must be QUIET before the caps are applied, ms. Tunable via
     * {@link #PROP_SETTLE_MS}.
     *
     * WHY NOT "800 ms after onDisplayAdded" (what 31/08 did): onDisplayAdded is the FIRST thing
     * that happens on a connect, not the last. WindowManager still has to build the display
     * area, SystemUI its status/nav bars and desktop-mode taskbar, Launcher its desktop session,
     * and the DP driver its link training -- each of which shows up as a burst of
     * onDisplayChanged. A simulated unplug/replug (see {@link #reprobe}) fired into the middle of
     * that teardown-and-rebuild is the "desktop features are glitchy until reboot" reported in XDA
     * #67 (NX123Dos): "make sure that adjustment is queued after system-queued reactions to
     * display connection event, means implement some delay". So: wait until the framework has
     * stopped talking about the display, then act. Every event on an external display restarts the
     * clock.
     */
    static final String PROP_SETTLE_MS = "persist.sys.rm.dp.settle_ms";
    static final int DEFAULT_SETTLE_MS = 3000;

    static int settleMs() {
        final String v = Prop.get(PROP_SETTLE_MS, "");
        try {
            final int ms = (v == null || v.isEmpty()) ? DEFAULT_SETTLE_MS : Integer.parseInt(v.trim());
            return Math.max(500, Math.min(30000, ms));
        } catch (NumberFormatException e) { return DEFAULT_SETTLE_MS; }
    }

    /**
     * Watch for screens being connected and re-apply the caps to each one, once the connection
     * has settled.
     *
     * edid_modes is not populated the instant the display id appears, so a scan that finds no
     * sink is retried a few times before giving up. A scan that DOES find one and needs a
     * re-probe causes its own remove/add pair, which lands back here, finds the override already
     * in place (changed == false) and does nothing further -- so the loop is self-limiting.
     */
    static void start(Context ctx) {
        if (sListener != null) return;
        final DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        if (dm == null) {
            Log.e(TAG, "no DisplayManager; external-display caps will not auto-apply");
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        sDm = dm;
        sListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY) return;
                // Screen recorders and MagicDesk also add displays; only a real DP sink populates
                // edid_modes, so that readback is the actual guard rather than the display type.
                schedule(ctx, h, 3);
            }

            @Override public void onDisplayRemoved(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY) return;
                // Unplug (real or our own simulated one): nothing to apply, and any scan queued
                // for the display that just went away must not fire into the gap.
                cancel(h);
            }

            @Override public void onDisplayChanged(int displayId) {
                // The framework is still reconfiguring this display: restart the quiet period,
                // but only if a scan is already queued (a change on an old, settled display is
                // not a reason to scan).
                if (displayId != Display.DEFAULT_DISPLAY && sPending != null) schedule(ctx, h, sRetriesLeft);
            }
        };
        dm.registerDisplayListener(sListener, h);

        // A screen can already be attached when the app starts (including a boot with the cable
        // in, which is exactly the case the reporter described). Boot is the busiest time of all
        // for the display stack, so the same settle rule applies.
        schedule(ctx, h, 3);
    }

    private static void cancel(Handler h) {
        if (sPending != null) { h.removeCallbacks(sPending); sPending = null; }
    }

    /** (Re)arm the settle timer; each call pushes the scan out by a full settle period. */
    private static void schedule(Context ctx, Handler h, int retries) {
        cancel(h);
        sRetriesLeft = retries;
        sPending = () -> {
            sPending = null;
            // A fresh connection with the kick enabled: change the mode first; the re-add that
            // causes comes back through here (inside KICK_QUIET_MS) and applies the real mode.
            if (autoKickDue() && kick(ctx)) return;
            final Result r = apply(ctx, true);
            if (!r.connected && sRetriesLeft > 1) schedule(ctx, h, sRetriesLeft - 1);
        };
        h.postDelayed(sPending, settleMs());
    }

    private ExternalDisplay() {}
}
