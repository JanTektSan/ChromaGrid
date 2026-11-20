package com.selfdev.chromagrid;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Tiny synthesized sound effects, generated once into the cache dir. */
final class Sfx {
    private static final int RATE = 22050;
    private final SoundPool pool;
    private final int pick, place, clear, clearBig, over;
    boolean enabled = true;

    Sfx(Context ctx) {
        pool = new SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        File dir = ctx.getCacheDir();
        pick = load(dir, "pick_v1", notes(new float[]{988}, 0.045f, 0.18f, 60f));
        place = load(dir, "place_v1", notes(new float[]{196}, 0.09f, 0.55f, 35f));
        clear = load(dir, "clear_v1", notes(new float[]{523, 659, 784}, 0.075f, 0.35f, 18f));
        clearBig = load(dir, "clearbig_v1", notes(new float[]{523, 659, 784, 1047, 1319}, 0.07f, 0.35f, 16f));
        over = load(dir, "over_v1", notes(new float[]{392, 330, 262, 196}, 0.16f, 0.35f, 9f));
    }

    void pick() { play(pick, 1f); }
    void place() { play(place, 1f); }
    void over() { play(over, 1f); }

    void clear(int lines, int combo) {
        float rate = Math.min(1.6f, 1f + 0.06f * Math.max(0, combo - 1));
        play(lines >= 3 ? clearBig : clear, rate);
    }

    void release() { pool.release(); }

    private void play(int id, float rate) {
        if (enabled && id > 0) pool.play(id, 1f, 1f, 1, 0, rate);
    }

    /** Sequence of plucked notes (sine + soft overtone, exponential decay). */
    private static short[] notes(float[] freqs, float noteDur, float vol, float decay) {
        int per = (int) (noteDur * RATE);
        int tail = (int) (0.25f * RATE);
        short[] out = new short[per * freqs.length + tail];
        for (int n = 0; n < freqs.length; n++) {
            float f = freqs[n];
            int start = n * per;
            int len = out.length - start;
            for (int i = 0; i < len; i++) {
                float t = i / (float) RATE;
                float env = (float) Math.exp(-decay * t) * Math.min(1f, i / 40f);
                float s = (float) (Math.sin(2 * Math.PI * f * t) + 0.3 * Math.sin(4 * Math.PI * f * t));
                int v = out[start + i] + (int) (s * env * vol * 22000);
                out[start + i] = (short) Math.max(-32768, Math.min(32767, v));
            }
        }
        return out;
    }

    private int load(File dir, String name, short[] pcm) {
        File f = new File(dir, name + ".wav");
        try {
            if (!f.exists()) {
                ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                b.put("RIFF".getBytes()).putInt(36 + pcm.length * 2).put("WAVE".getBytes());
                b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                        .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
                b.put("data".getBytes()).putInt(pcm.length * 2);
                for (short s : pcm) b.putShort(s);
                try (FileOutputStream os = new FileOutputStream(f)) { os.write(b.array()); }
            }
            return pool.load(f.getAbsolutePath(), 1);
        } catch (IOException e) {
            return 0;
        }
    }
}
