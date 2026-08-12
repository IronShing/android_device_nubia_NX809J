package com.nx809j.voiprecorder;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Captures both sides of a VoIP call and writes a 16-bit / 48 kHz stereo WAV:
 *   Left  channel = uplink   (your mic, echo-cancelled VOICE_COMMUNICATION source)
 *   Right channel = downlink (the other party, USAGE_VOICE_COMMUNICATION output
 *                             tapped via a dynamic AudioPolicy loop-back mix)
 *
 * Keeping the two sides on separate channels avoids sample-mixing/drift math and
 * lets the user hear each party distinctly. Both AudioRecords run at the same
 * rate so blocking reads of equal size stay roughly in lock-step.
 */
final class CallRecorder {
    private static final String TAG = "VoipRecorder";
    private static final int SR = 48000;
    private static final int FRAMES = 1024;              // per read

    private final Context ctx;
    private AudioRecord upRec;                            // mic
    private AudioRecord downRec;                          // far end (policy sink)
    private AudioPolicy policy;
    private Thread worker;
    private volatile boolean running;
    private File outFile;

    CallRecorder(Context ctx) { this.ctx = ctx; }

    File getOutputFile() { return outFile; }

    /** @return true if capture started. */
    boolean start(String tag) {
        AudioManager am = ctx.getSystemService(AudioManager.class);

        // ---- downlink: dynamic AudioPolicy loop-back on USAGE_VOICE_COMMUNICATION ----
        AudioFormat mixFmt = new AudioFormat.Builder()
                .setSampleRate(SR)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        AudioMixingRule rule = new AudioMixingRule.Builder()
                .addRule(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build(),
                        AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
                .build();
        AudioMix mix = new AudioMix.Builder(rule)
                .setFormat(mixFmt)
                .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK)   // capture a copy; call still plays
                .build();
        policy = new AudioPolicy.Builder(ctx).addMix(mix).build();
        int r = am.registerAudioPolicy(policy);
        if (r != AudioManager.SUCCESS) {
            Log.e(TAG, "registerAudioPolicy failed: " + r);
            cleanup();
            return false;
        }
        downRec = policy.createAudioRecordSink(mix);
        if (downRec == null || downRec.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "createAudioRecordSink failed");
            cleanup();
            return false;
        }

        // ---- uplink: VOICE_COMMUNICATION mic ----
        int minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        upRec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf, FRAMES * 2 * 4));
        if (upRec.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "uplink AudioRecord init failed");
            cleanup();
            return false;
        }

        outFile = newOutFile(tag);
        running = true;
        try {
            upRec.startRecording();
            downRec.startRecording();
        } catch (IllegalStateException e) {
            Log.e(TAG, "startRecording failed", e);
            cleanup();
            return false;
        }

        worker = new Thread(this::loop, "voiprec-capture");
        worker.start();
        Log.i(TAG, "recording -> " + outFile);
        return true;
    }

    void stop() {
        running = false;
        if (worker != null) {
            try { worker.join(2000); } catch (InterruptedException ignored) {}
        }
        cleanup();
    }

    // ---------------------------------------------------------------------

    private void loop() {
        short[] up = new short[FRAMES];
        short[] down = new short[FRAMES];
        byte[] out = new byte[FRAMES * 2 * 2];           // stereo, 16-bit
        RandomAccessFile raf = null;
        long dataBytes = 0;
        try {
            raf = new RandomAccessFile(outFile, "rw");
            raf.setLength(0);
            writeWavHeader(raf, 0);                       // placeholder
            while (running) {
                int nu = upRec.read(up, 0, FRAMES);
                int nd = downRec.read(down, 0, FRAMES);
                if (nu < 0) nu = 0;
                if (nd < 0) nd = 0;
                int n = Math.max(nu, nd);
                if (n == 0) continue;
                int oi = 0;
                for (int i = 0; i < n; i++) {
                    short l = i < nu ? up[i] : 0;
                    short rr = i < nd ? down[i] : 0;
                    out[oi++] = (byte) (l & 0xff);
                    out[oi++] = (byte) ((l >> 8) & 0xff);
                    out[oi++] = (byte) (rr & 0xff);
                    out[oi++] = (byte) ((rr >> 8) & 0xff);
                }
                raf.write(out, 0, oi);
                dataBytes += oi;
            }
        } catch (Exception e) {
            Log.e(TAG, "capture loop error", e);
        } finally {
            if (raf != null) {
                try { writeWavHeader(raf, dataBytes); raf.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void cleanup() {
        try { if (upRec != null) { upRec.stop(); upRec.release(); } } catch (Exception ignored) {}
        try { if (downRec != null) { downRec.stop(); downRec.release(); } } catch (Exception ignored) {}
        try {
            if (policy != null) {
                AudioManager am = ctx.getSystemService(AudioManager.class);
                am.unregisterAudioPolicy(policy);
            }
        } catch (Exception ignored) {}
        upRec = null; downRec = null; policy = null;
    }

    private static File newOutFile(String tag) {
        File dir = new File(android.os.Environment.getExternalStorageDirectory(), "CallRecordings");
        if (!dir.exists()) dir.mkdirs();
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                java.util.Locale.US).format(new java.util.Date());
        String safe = tag == null ? "voip" : tag.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(dir, safe + "_" + stamp + ".wav");
    }

    private static void writeWavHeader(RandomAccessFile raf, long dataBytes) throws Exception {
        int channels = 2, bits = 16;
        long byteRate = (long) SR * channels * bits / 8;
        raf.seek(0);
        raf.writeBytes("RIFF");
        writeLE(raf, (int) (36 + dataBytes));
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        writeLE(raf, 16);
        writeLE(raf, (short) 1);                          // PCM
        writeLE(raf, (short) channels);
        writeLE(raf, SR);
        writeLE(raf, (int) byteRate);
        writeLE(raf, (short) (channels * bits / 8));      // block align
        writeLE(raf, (short) bits);
        raf.writeBytes("data");
        writeLE(raf, (int) dataBytes);
    }

    private static void writeLE(RandomAccessFile raf, int v) throws Exception {
        raf.write(v & 0xff); raf.write((v >> 8) & 0xff);
        raf.write((v >> 16) & 0xff); raf.write((v >> 24) & 0xff);
    }
    private static void writeLE(RandomAccessFile raf, short v) throws Exception {
        raf.write(v & 0xff); raf.write((v >> 8) & 0xff);
    }
}
