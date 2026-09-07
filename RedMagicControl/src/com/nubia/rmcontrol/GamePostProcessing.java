package com.nubia.rmcontrol;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Qualcomm Game Post Processing (GPP): AI super-resolution and frame interpolation
 * for games, run on the Hexagon NPU through the vendor HexLP service. This is what
 * stock RedMagic OS sells as the "R4 gaming chip" super-resolution / super-frame.
 *
 * Plumbing: libgui's QtiSurfaceExtensionGPP (frameworks/native, nx809j-gpp) swaps a
 * game's EGL SurfaceView producer for one served by /system/bin/gppservice, which
 * renders the frames through libgpphexlpsession -> HexLP. Both sides read the
 * vendor.gpp.* properties below; none of them persist across a reboot, so the user's
 * choice is kept in persist.sys.rm.gpp.* and pushed out again from RmApp at boot.
 * The property protocol is the one stock services.jar (MindSyncService.dumpGfrc)
 * speaks, so the blobs see exactly what they see on stock.
 *
 * vendor.gpp.frc.enable: 0x22 = dynamic on, 0x21 = dynamic off (libgui polls it on
 * every dequeue, so a change applies to a game that is already running). The other
 * values are gppservice-side knobs. gppservice only touches packages listed in
 * /system/etc/gpp_app_list unless allgame is set, and it can process only one
 * surface at a time.
 */
final class GamePostProcessing {
    private static final String TAG = "RMControl.GPP";

    static final String PROP_ENABLED = "persist.sys.rm.gpp";
    static final String PROP_ALLGAME = "persist.sys.rm.gpp.allgame";
    static final String PROP_INTERP  = "persist.sys.rm.gpp.interp";
    static final String PROP_UPSCALE = "persist.sys.rm.gpp.upscale";   // 0 off, 1, 2

    static final boolean DEF_ENABLED = false;
    static final boolean DEF_ALLGAME = false;
    static final boolean DEF_INTERP  = true;
    static final int     DEF_UPSCALE = 1;

    /** Push the persisted choice out to the live vendor.gpp.* properties. */
    static void apply() {
        boolean on = Prop.getBool(PROP_ENABLED, DEF_ENABLED);
        if (on) {
            boolean allgame = Prop.getBool(PROP_ALLGAME, DEF_ALLGAME);
            boolean interp  = Prop.getBool(PROP_INTERP, DEF_INTERP);
            int upscale = clamp(parse(Prop.get(PROP_UPSCALE, Integer.toString(DEF_UPSCALE)),
                    DEF_UPSCALE), 0, 2);
            // Same order MindSyncService uses: knobs first, then the enable that libgui polls.
            Prop.set("vendor.gpp.allgame.enable", allgame ? "1" : "0");
            Prop.set("vendor.gpp.gfrc.interp.rate", interp ? "1" : "0");
            Prop.set("vendor.gpp.gfrc.upscale.ratio", Integer.toString(upscale));
            Prop.set("vendor.gpp.dynamic.settings.enable", "1");
            Prop.set("vendor.gpp.frc.enable", "0x22");
            Log.i(TAG, "on: allgame=" + allgame + " interp=" + interp + " upscale=" + upscale);
        } else {
            Prop.set("vendor.gpp.frc.enable", "0x21");
            Prop.set("vendor.gpp.dynamic.settings.enable", "0");
            Prop.set("vendor.gpp.allgame.enable", "0");
            Log.i(TAG, "off");
        }
    }

    /** Qualcomm's allowlist: package, upscale (0-2), interp (0/1). Comments start with '#'. */
    static final String APP_LIST = "/system/etc/gpp_app_list";

    static final class Entry {
        final String pkg; final int upscale; final boolean interp;
        Entry(String pkg, int upscale, boolean interp) {
            this.pkg = pkg; this.upscale = upscale; this.interp = interp;
        }
    }

    /** Parses the shipped allowlist; empty if the file is missing (GPP not built in). */
    static List<Entry> readAppList() {
        final List<Entry> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(APP_LIST))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                final String[] f = line.split(",");
                if (f.length < 3) continue;
                out.add(new Entry(f[0].trim(), clamp(parse(f[1], 1), 0, 2), !"0".equals(f[2].trim())));
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot read " + APP_LIST, e);
        }
        return out;
    }

    /**
     * Store names for the entries a user outside China can actually install. Everything else in
     * Qualcomm's list is a Chinese-store build (nearme/aligames/bilibili/nubia suffixes) and is
     * shown by package name.
     */
    private static final String[][] KNOWN = {
        {"com.tencent.ig", "PUBG Mobile"},
        {"com.pubg.krmobile", "PUBG Mobile (KR)"},
        {"com.tencent.tmgp.pubgmhd", "Game for Peace (PUBG, CN)"},
        {"com.tencent.tmgp.pubgm", "PUBG Mobile (CN)"},
        {"com.miHoYo.GenshinImpact", "Genshin Impact"},
        {"com.miHoYo.Yuanshen", "Genshin Impact (CN)"},
        {"com.HoYoverse.hkrpgoversea", "Honkai: Star Rail"},
        {"com.miHoYo.hkrpg", "Honkai: Star Rail (CN)"},
        {"com.HoYoverse.Nap", "Zenless Zone Zero"},
        {"com.miHoYo.Nap", "Zenless Zone Zero (CN)"},
        {"com.miHoYo.bh3global", "Honkai Impact 3rd"},
        {"com.miHoYo.bh3oversea", "Honkai Impact 3rd (SEA)"},
        {"com.miHoYo.bh3tw", "Honkai Impact 3rd (TW)"},
        {"com.miHoYo.bh3rdJP", "Honkai Impact 3rd (JP)"},
        {"com.tencent.tmgp.bh3", "Honkai Impact 3rd (CN)"},
        {"com.activision.callofduty.shooter", "Call of Duty: Mobile"},
        {"com.tencent.tmgp.cod", "Call of Duty: Mobile (CN)"},
        {"com.kurogame.wutheringwaves.global", "Wuthering Waves"},
        {"com.kurogame.mingchao", "Wuthering Waves (CN)"},
        {"com.kurogame.gplay.punishing.grayraven.en", "Punishing: Gray Raven"},
        {"com.herogame.gplay.punishing.grayraven.jp", "Punishing: Gray Raven (JP)"},
        {"com.riotgames.league.wildrift", "League of Legends: Wild Rift"},
        {"com.tencent.lolm", "League of Legends: Wild Rift (CN)"},
        {"com.mobile.legends", "Mobile Legends: Bang Bang"},
        {"net.wargaming.wot.blitz", "World of Tanks Blitz"},
        {"com.bandainamcoent.opbrww", "One Piece Bounty Rush"},
        {"com.proximabeta.nikke", "Goddess of Victory: Nikke"},
        {"com.netease.eggypartyen", "Eggy Party"},
        {"com.netease.party", "Eggy Party (CN)"},
        {"com.kiloo.subwaysurf", "Subway Surfers"},
        {"com.imangi.templerun2", "Temple Run 2"},
        {"com.tgc.sky.android", "Sky: Children of the Light"},
        {"com.netease.sky", "Sky: Children of the Light (CN)"},
        {"com.tencent.tmgp.supercell.clashofclans", "Clash of Clans (CN)"},
        {"com.tencent.tmgp.supercell.clashroyale", "Clash Royale (CN)"},
        {"com.tencent.tmgp.supercell.boombeach", "Boom Beach (CN)"},
        {"com.tencent.tmgp.sgame", "Honor of Kings (CN)"},
        {"com.tencent.pokemonunite.cn", "Pok\u00e9mon UNITE (CN)"},
        {"com.tencent.fifamobile", "FIFA Mobile (CN)"},
        {"com.tencent.tmgp.cf", "CrossFire Mobile (CN)"},
        {"com.tencent.tmgp.speedmobile", "QQ Speed (CN)"},
        {"com.tencent.tmgp.dnf", "Dungeon & Fighter Mobile (CN)"},
        {"com.tencent.KiHan", "Naruto Mobile (CN)"},
        {"com.hypergryph.arknights", "Arknights (CN)"},
        {"com.bilibili.azurlane", "Azur Lane (CN)"},
        {"com.Sunborn.SnqxExilium", "Girls' Frontline 2: Exilium (CN)"},
        {"com.sofunny.Sausage", "Sausage Man (CN)"},
        {"com.netease.dwrg", "Identity V (CN)"},
        {"com.lilithgames.rok.offical.cn", "Rise of Kingdoms (CN)"},
        {"com.miHoYo.enterprise.NGHSoD", "Tears of Themis (CN)"},
        {"com.ustwo.monumentvalleyzz", "Monument Valley (CN)"},
    };

    /** Known store name for a listed package, else null. */
    static String knownName(String pkg) {
        for (String[] k : KNOWN) if (k[0].equals(pkg)) return k[1];
        return null;
    }

    /** Installed app label for a package, or null when it is not installed. */
    static String installedLabel(Context ctx, String pkg) {
        try {
            final PackageManager pm = ctx.getPackageManager();
            final ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            final CharSequence l = pm.getApplicationLabel(ai);
            return l == null ? pkg : l.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** "Label (package)" for every listed game that is installed, sorted by label. */
    static List<String> installedListedGames(Context ctx) {
        final List<String> out = new ArrayList<>();
        for (Entry e : readAppList()) {
            final String l = installedLabel(ctx, e.pkg);
            if (l != null) out.add(l.equals(e.pkg) ? e.pkg : l + " (" + e.pkg + ")");
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private GamePostProcessing() {}
}
