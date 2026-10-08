package com.nubia.rmcontrol;

import android.app.AlertDialog;
import android.content.res.Configuration;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.util.List;

/**
 * QS tile "Audio output": shows where media plays now; tap pops the "Play media on" picker as a
 * dialog OVER the open shade (showDialog + our SystemUI no-collapse for this package), so switching
 * output keeps you in the pull-down (user request 2026-10-04). Android's own switcher lists no USB
 * sinks, so it can't take sound back from the XReal glasses (verified 2026-10-03); this one pins
 * the MEDIA strategy to the chosen device.
 */
public class AudioOutputTile extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    private void refresh() {
        final Tile t = getQsTile();
        if (t == null) return;
        final AudioDeviceInfo now = AudioOutput.current(this);
        t.setState(AudioOutput.pinned(this) != null ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(now != null ? AudioOutput.label(now) : null);
        t.updateTile();
    }

    @Override
    public void onClick() {
        final List<AudioDeviceInfo> outs = AudioOutput.outputs(this);
        final AudioDeviceAttributes pinned = AudioOutput.pinned(this);
        final String[] items = new String[outs.size() + 1];
        int checked = 0;
        items[0] = "Automatic (newest device)";
        for (int i = 0; i < outs.size(); i++) {
            items[i + 1] = AudioOutput.label(outs.get(i));
            if (AudioOutput.same(pinned, outs.get(i))) checked = i + 1;
        }
        final boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        final AlertDialog d = new AlertDialog.Builder(this, night
                        ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                        : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Play media on")
                .setSingleChoiceItems(items, checked, (dlg, which) -> {
                    AudioOutput.pin(this, which == 0 ? null : outs.get(which - 1));
                    refresh();                 // update the tile subtitle; shade stays open
                    dlg.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        // showDialog puts it in a TYPE_QS_DIALOG window over the shade; SystemUI is patched not to
        // collapse the shade for com.nubia.rmcontrol (TileServices.onShowDialog).
        showDialog(d);
    }
}
