package com.nx809j.voiprecorder;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
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

/** Simple browser for /sdcard/CallRecordings — tap to play, long-press to delete. */
public class RecordingsActivity extends Activity {
    private File dir;
    private final List<File> files = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dir = new File(Environment.getExternalStorageDirectory(), "CallRecordings");
        ListView lv = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> play(files.get(pos)));
        lv.setOnItemLongClickListener((p, v, pos, id) -> { confirmDelete(files.get(pos)); return true; });

        TextView empty = new TextView(this);
        empty.setText(R.string.no_recordings);
        empty.setPadding(48, 48, 48, 48);
        empty.setVisibility(View.GONE);
        addContentView(empty, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        lv.setEmptyView(empty);
        setContentView(lv);
    }

    @Override protected void onResume() { super.onResume(); reload(); }

    private void reload() {
        files.clear();
        adapter.clear();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".wav"));
        if (fs != null) {
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
        if (n.startsWith("com.whatsapp")) return "WhatsApp call";
        int u = n.indexOf('_');
        return u > 0 ? n.substring(0, u) : n;
    }

    private void play(File f) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(f), "audio/x-wav");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); }
        catch (Exception e) { Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show(); }
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
