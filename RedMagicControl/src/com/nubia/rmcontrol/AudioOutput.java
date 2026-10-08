package com.nubia.rmcontrol;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioProductStrategy;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Where media plays. Android routes media to whatever connected last (the XReal glasses take it as
 * a USB headset the moment they're plugged in) and its own output switcher does not offer USB
 * sinks, so we pin the MEDIA product strategy to the device the user picks
 * (setPreferredDeviceForStrategy; RM Control is system uid = MODIFY_AUDIO_ROUTING). The pin
 * survives the glasses reconnecting; "Automatic" removes it. User request 2026-10-03.
 */
final class AudioOutput {
    private static final String TAG = "RmAudioOut";
    private static final AudioAttributes MEDIA =
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();

    static AudioProductStrategy mediaStrategy() {
        for (AudioProductStrategy s : AudioManager.getAudioProductStrategies()) {
            if (s.supportsAudioAttributes(MEDIA)) return s;
        }
        return null;
    }

    /** Selectable media outputs, one per physical device (A2DP/BLE/USB/wired + phone speaker). */
    static List<AudioDeviceInfo> outputs(Context ctx) {
        final AudioManager am = ctx.getSystemService(AudioManager.class);
        final List<AudioDeviceInfo> out = new ArrayList<>();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_HDMI:
                    break;
                default:
                    continue;
            }
            boolean dup = false;   // a device can expose several ports of one type
            for (AudioDeviceInfo o : out) {
                if (o.getType() == d.getType() && o.getAddress().equals(d.getAddress())) { dup = true; break; }
            }
            if (!dup) out.add(d);
        }
        return out;
    }

    static String label(AudioDeviceInfo d) {
        if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return "This phone";
        final CharSequence n = d.getProductName();
        final String name = n != null && n.length() > 0 ? n.toString() : "Audio device";
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE: return name + " (USB)";
            case AudioDeviceInfo.TYPE_HDMI: return name + " (display)";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Wired headphones";
            default: return name;
        }
    }

    /** The pinned device, or null when Android chooses (Automatic). */
    static AudioDeviceAttributes pinned(Context ctx) {
        final AudioProductStrategy s = mediaStrategy();
        if (s == null) return null;
        try {
            return ctx.getSystemService(AudioManager.class).getPreferredDeviceForStrategy(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** Where media goes right now (pinned or not). */
    static AudioDeviceInfo current(Context ctx) {
        try {
            final List<AudioDeviceInfo> d =
                    ctx.getSystemService(AudioManager.class).getAudioDevicesForAttributes(MEDIA);
            return d.isEmpty() ? null : d.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean same(AudioDeviceAttributes a, AudioDeviceInfo d) {
        return a != null && a.getType() == d.getType() && a.getAddress().equals(d.getAddress());
    }

    /** d == null: back to Automatic. */
    static void pin(Context ctx, AudioDeviceInfo d) {
        final AudioProductStrategy s = mediaStrategy();
        if (s == null) { Log.w(TAG, "no media strategy"); return; }
        final AudioManager am = ctx.getSystemService(AudioManager.class);
        final boolean ok = d == null ? am.removePreferredDeviceForStrategy(s)
                : am.setPreferredDeviceForStrategy(s, new AudioDeviceAttributes(d));
        Log.i(TAG, "media -> " + (d == null ? "automatic" : label(d)) + " ok=" + ok);
    }

    private AudioOutput() {}
}
