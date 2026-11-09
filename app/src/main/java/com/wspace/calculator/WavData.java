package com.wspace.calculator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Minimal RIFF/WAVE reader (16-bit PCM, mono or stereo) - pure Java so it can be unit-tested. */
final class WavData {
    final short[] pcm;      // interleaved samples
    final int rate;
    final int channels;

    private WavData(short[] pcm, int rate, int channels) {
        this.pcm = pcm;
        this.rate = rate;
        this.channels = channels;
    }

    double seconds() { return pcm.length / (double) (rate * channels); }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static WavData read(InputStream in) throws IOException {
        return parse(readAll(in));
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }

    private static boolean tag(byte[] b, int o, String t) {
        for (int i = 0; i < 4; i++) if (b[o + i] != (byte) t.charAt(i)) return false;
        return true;
    }

    static WavData parse(byte[] b) throws IOException {
        if (b.length < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) throw new IOException("not a WAV file");
        int pos = 12, rate = 0, channels = 0, bits = 0;
        while (pos + 8 <= b.length) {
            int size = le32(b, pos + 4);
            int body = pos + 8;
            if (tag(b, pos, "fmt ")) {
                int format = le16(b, body);
                channels = le16(b, body + 2);
                rate = le32(b, body + 4);
                bits = le16(b, body + 14);
                if (format != 1 || bits != 16) throw new IOException("only 16-bit PCM is supported");
            } else if (tag(b, pos, "data")) {
                if (rate == 0) throw new IOException("data before fmt");
                int len = size < 0 || body + size > b.length ? b.length - body : size;
                short[] pcm = new short[len / 2];
                for (int i = 0; i < pcm.length; i++) pcm[i] = (short) le16(b, body + i * 2);
                return new WavData(pcm, rate, channels);
            }
            if (size < 0) break;
            pos = body + size + (size & 1);
        }
        throw new IOException("no data chunk");
    }
}
