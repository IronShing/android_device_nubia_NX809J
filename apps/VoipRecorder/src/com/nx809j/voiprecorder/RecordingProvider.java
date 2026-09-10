package com.nx809j.voiprecorder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal read-only content provider so a recording can be handed to a player app.
 * file:// URIs are rejected by StrictMode on a current targetSdk (FileUriExposedException),
 * which surfaced as the "No audio player installed" toast. Not exported; the player only
 * gets the per-URI grant from the ACTION_VIEW intent. Only files directly inside
 * /sdcard/CallRecordings are served (no path traversal).
 */
public class RecordingProvider extends ContentProvider {
    static final String AUTHORITY = "com.nx809j.voiprecorder.recordings";

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(f.getName()).build();
    }

    private static File dir() {
        return new File(Environment.getExternalStorageDirectory(), "CallRecordings");
    }

    private static File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File f = new File(dir(), name);
        if (!f.isFile()) throw new FileNotFoundException(String.valueOf(uri));
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "audio/x-wav"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        if (projection == null) {
            projection = new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        }
        MatrixCursor c = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) { return 0; }
}
