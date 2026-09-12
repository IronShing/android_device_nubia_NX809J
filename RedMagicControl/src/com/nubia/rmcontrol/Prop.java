package com.nubia.rmcontrol;

import android.os.SystemProperties;
import android.util.Log;

/**
 * System-property helper. The app is built with platform_apis + platform cert, so
 * it links android.os.SystemProperties directly. The previous reflection-based
 * version failed silently under A16's hidden-API path (getMethod/invoke returned
 * no-ops), so every get returned the default and every set did nothing — which is
 * why the tiles/panel changed no properties while root `setprop` worked fine.
 */
final class Prop {

    static boolean getBool(String key, boolean def) {
        try { return SystemProperties.getBoolean(key, def); }
        catch (Throwable t) { return def; }
    }

    static String get(String key, String def) {
        try { return SystemProperties.get(key, def); }
        catch (Throwable t) { return def; }
    }

    static int getInt(String key, int def) {
        try { return SystemProperties.getInt(key, def); }
        catch (Throwable t) { return def; }
    }

    static void set(String key, String val) {
        try { SystemProperties.set(key, val); }
        catch (Throwable t) { Log.e("RMControl", "Prop.set " + key + "=" + val + " failed", t); }
    }

    private Prop() {}
}
