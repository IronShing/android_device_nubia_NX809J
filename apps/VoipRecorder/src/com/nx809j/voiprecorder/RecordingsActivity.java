package com.nx809j.voiprecorder;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Simple browser for /sdcard/CallRecordings — tap to play, long-press to delete. Opened from
 * RedMagic Control (Privacy tab); the menu carries the 15 s pipeline self-test that used to sit
 * on the consent screen.
 */
public class RecordingsActivity extends Activity {
    private File dir;
    private final List<File> files = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dir = new File(Environment.getExternalStorageDirectory(), "CallRecordings");
        final float dp = getResources().getDisplayMetrics().density;

        // Own header instead of the framework action bar: on a current targetSdk the window is
        // edge-to-edge and ActionBarOverlayLayout both offsets the bar by the status-bar inset
        // and lets the content start under it, so the title sat under the status bar and the
        // first rows under the bar. One plain root we pad with the insets ourselves is exact.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding((int) (16 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
        TextView title = new TextView(this);
        title.setText(R.string.recordings_title);
        title.setTextSize(22);
        title.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Large);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button test = new Button(this, null, android.R.attr.borderlessButtonStyle);
        test.setText(R.string.selftest_label);
        test.setOnClickListener(v -> runSelfTest());
        header.addView(test);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView lv = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> play(files.get(pos)));
        lv.setOnItemLongClickListener((p, v, pos, id) -> { confirmDelete(files.get(pos)); return true; });
        TextView empty = new TextView(this);
        empty.setText(R.string.no_recordings);
        empty.setPadding(48, 48, 48, 48);
        empty.setVisibility(View.GONE);
        lv.setEmptyView(empty);
        FrameLayout body = new FrameLayout(this);
        body.addView(lv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Keep everything out of the status bar, cutout and gesture bar.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets si = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(si.left, si.top, si.right, si.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); reload(); }

    /** Exercise the capture pipeline for 15 s without needing a live call. */
    private void runSelfTest() {
        final CallRecorder r = new CallRecorder(this);
        if (!r.start("Test")) {
            Toast.makeText(this, R.string.selftest_fail, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.selftest_running, Toast.LENGTH_SHORT).show();
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            r.stop();
            Toast.makeText(this, getString(R.string.selftest_saved) + "\n" + r.getOutputFile(),
                    Toast.LENGTH_LONG).show();
            reload();
        }, 15000);
    }

    private void reload() {
        files.clear();
        adapter.clear();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".wav"));
        if (fs != null) {
            // 44-byte files are a WAV header with no audio (sessions that captured nothing,
            // e.g. the pre-09-09 builds arming on cellular calls); they are noise, drop them.
            List<File> keep = new ArrayList<>();
            for (File f : fs) { if (f.length() <= CallRecorder.WAV_HEADER_BYTES) f.delete(); else keep.add(f); }
            fs = keep.toArray(new File[0]);
            Arrays.sort(fs, Comparator.comparingLong(File::lastModified).reversed());
            SimpleDateFormat fmt = new SimpleDateFormat("MMM d, HH:mm", Locale.US);
            for (File f : fs) {
                files.add(f);
                long kb = f.length() / 1024;
                adapter.add(prettyName(f) + "\n" + fmt.format(new Date(f.lastModified()))
                        + "  •  " + kb + " KB");
            }
        }
        adapter.notifyDataSetChanged();
    }

    private static String prettyName(File f) {
        String n = f.getName();
        java.util.regex.Matcher m = CallRecorder.NAME.matcher(n);
        if (m.matches()) return m.group(1);                 // "<App> - <Contact>"
        // pre-09-10 files: "<pkg>_<stamp>.wav"
        if (n.startsWith("com.whatsapp")) return "WhatsApp call";
        int u = n.indexOf('_');
        return u > 0 ? n.substring(0, u) : n;
    }

    private void play(File f) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(RecordingProvider.uriFor(f), "audio/x-wav");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, null)); }
        catch (Exception e) {
            android.util.Log.w("VoipRecorder", "play failed", e);
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(File f) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.delete_q)
                .setMessage(f.getName())
                .setPositiveButton(android.R.string.ok, (d, w) -> { f.delete(); reload(); })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
