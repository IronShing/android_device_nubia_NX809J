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

    static int maxHz() { return capOf(PROP_MAX_HZ); }

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

        Result(boolean connected, Mode applied, boolean changed, boolean fits) {
            this.connected = connected; this.applied = applied;
            this.changed = changed; this.fits = fits;
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
        if (modes.isEmpty()) return new Result(false, null, false, true);

        final Mode want = pick(modes);
        if (want == null) return new Result(true, null, false, true);

        // Did the caps actually get honoured, or did pick() fall back to native?
        final int capH = maxRes(), capHz = maxHz();
        final boolean fits = (capH == 0 || want.h <= capH) && (capHz == 0 || want.rate <= capHz);

        final String wire = want.wire();
        final String last = Prop.get(PROP_LAST_WIRE, "");
        final boolean changed = !wire.equals(last);

        if (changed && write(wire)) {
            Prop.set(PROP_LAST_WIRE, wire);
            if (allowReprobe && Prop.getBool(PROP_AUTO_REPROBE, true)) reprobe();
        }
        return new Result(true, want, changed, fits);
    }

    /**
     * Last wire string we wrote, so a reconnect that resolves to the same mode does not trigger a
     * pointless simulated hotplug. Not the source of truth for anything else: nubia's
     * store_user_edid_modes persists a per-monitor choice inside the driver, so the panel can come
     * up on a stored mode that this property knows nothing about (observed 2026-08-30).
     */
    static final String PROP_LAST_WIRE = "persist.sys.rm.dp.last";

    private static DisplayManager sDm;
    private static DisplayManager.DisplayListener sListener;

    /**
     * Watch for screens being connected and re-apply the caps to each one.
     *
     * edid_modes is not populated the instant the display id appears, so each add is retried a
     * few times before giving up.
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
                retry(ctx, h, 3);
            }

            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {}
        };
        dm.registerDisplayListener(sListener, h);

        // A screen can already be attached when the app starts (including a boot with the cable
        // in, which is exactly the case the reporter described).
        retry(ctx, h, 3);
    }

    private static void retry(Context ctx, Handler h, int left) {
        h.postDelayed(() -> {
            final Result r = apply(ctx, true);
            if (!r.connected && left > 1) retry(ctx, h, left - 1);
        }, 800);
    }

    private ExternalDisplay() {}
}
