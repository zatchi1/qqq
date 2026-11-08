package com.wspace.calculator;

import java.util.Random;

/**
 * Tiny offline synthesiser. Every effect and the music loop are generated from maths at start-up,
 * so the app ships no audio files and copies no recordings. Pure Java (testable on a desktop JVM).
 */
final class Synth {

    static final int RATE = 22050;
    private static final Random RND = new Random(42);

    private Synth() {}

    // ------------------------------------------------------------ primitives
    static float[] zeros(double sec) { return new float[(int) Math.round(sec * RATE)]; }

    static void mix(float[] dst, float[] src, double atSec, float gain) {
        int off = (int) Math.round(atSec * RATE);
        for (int i = 0; i < src.length; i++) {
            int j = off + i;
            if (j < 0) continue;
            if (j >= dst.length) break;
            dst[j] += src[i] * gain;
        }
    }

    static float osc(int wave, double phase) {
        double x = phase - Math.floor(phase);
        switch (wave) {
            case 0: return (float) Math.sin(2 * Math.PI * x);
            case 1: return x < 0.5 ? 1f : -1f;
            case 2: return (float) (2 * x - 1);
            default: return (float) (x < 0.5 ? 4 * x - 1 : 3 - 4 * x);
        }
    }

    /** Exponential pitch sweep f0 -> f1 with attack and exponential decay. wave: 0 sin,1 sq,2 saw,3 tri. */
    static float[] tone(double sec, double f0, double f1, int wave, double attack, double decay) {
        float[] out = zeros(sec);
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE;
            double frac = t / sec;
            double f = f0 * Math.pow(f1 / f0, frac);
            phase += f / RATE;
            double env = Math.min(1.0, t / Math.max(attack, 1e-4)) * Math.exp(-t * decay)
                    * Math.min(1.0, (sec - t) / 0.004);
            out[i] = (float) (osc(wave, phase) * env);
        }
        return out;
    }

    /** Frequency-modulated "flying saucer" warble. */
    static float[] ufo(double sec, double base, double depth, double rateHz, int wave, double decay) {
        float[] out = zeros(sec);
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE;
            double f = base + depth * Math.sin(2 * Math.PI * rateHz * t);
            phase += f / RATE;
            double env = Math.min(1.0, t / 0.01) * Math.exp(-t * decay) * Math.min(1.0, (sec - t) / 0.01);
            out[i] = (float) (osc(wave, phase) * env);
        }
        return out;
    }

    /** Comedic "boing". */
    static float[] boing(double sec) {
        float[] out = zeros(sec);
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE;
            double f = 70 + 260 * Math.exp(-t * 2.4) + 70 * Math.sin(2 * Math.PI * 21 * t) * Math.exp(-t * 3.5);
            phase += f / RATE;
            double env = Math.min(1.0, t / 0.003) * Math.exp(-t * 3.2) * Math.min(1.0, (sec - t) / 0.01);
            out[i] = (float) (Math.sin(2 * Math.PI * phase) * env);
        }
        return out;
    }

    /** White noise through a one-pole lowpass (lp 0..1, 1 = no filtering) with exponential decay. */
    static float[] noise(double sec, double decay, double lp) {
        float[] out = zeros(sec);
        double y = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE;
            double x = RND.nextDouble() * 2 - 1;
            y += lp * (x - y);
            out[i] = (float) (y * Math.exp(-t * decay) * Math.min(1.0, (sec - t) / 0.004));
        }
        return out;
    }

    /** Hi-hat like burst: differentiated noise. */
    static float[] hat(double sec, double decay) {
        float[] out = zeros(sec);
        double prev = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE;
            double x = RND.nextDouble() * 2 - 1;
            out[i] = (float) ((x - prev) * 0.5 * Math.exp(-t * decay));
            prev = x;
        }
        return out;
    }

    /** Noise whose filter sweeps from f0 to f1 Hz (whoosh / zip). Bell-shaped loudness. */
    static float[] swoosh(double sec, double f0, double f1) {
        float[] out = zeros(sec);
        double y1 = 0, y2 = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE;
            double frac = t / sec;
            double f = f0 * Math.pow(f1 / f0, frac);
            double a = Math.min(0.95, 2 * Math.PI * f / RATE);
            double x = RND.nextDouble() * 2 - 1;
            y1 += a * (x - y1);
            y2 += a * (y1 - y2);
            double env = Math.sin(Math.PI * frac);
            out[i] = (float) (y2 * 2.2 * env);
        }
        return out;
    }

    static float[] lowpass(float[] in, double coef) {
        float[] out = new float[in.length];
        double y = 0;
        for (int i = 0; i < in.length; i++) {
            y += coef * (in[i] - y);
            out[i] = (float) y;
        }
        return out;
    }

    static void saturate(float[] a, double drive) {
        for (int i = 0; i < a.length; i++) a[i] = (float) Math.tanh(a[i] * drive);
    }

    /** Multiplies by a quadratic ramp 0 -> 1 (risers). */
    static void ramp(float[] a) {
        for (int i = 0; i < a.length; i++) {
            double k = (double) i / a.length;
            a[i] *= (float) (k * k);
        }
    }

    static float[] scaled(float[] a, float g) {
        float[] o = new float[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i] * g;
        return o;
    }

    static void normalize(float[] a, float peak) {
        float m = 1e-6f;
        for (float v : a) m = Math.max(m, Math.abs(v));
        float g = peak / m;
        for (int i = 0; i < a.length; i++) a[i] *= g;
    }

    static short[] toPcm(float[] a) {
        short[] out = new short[a.length];
        for (int i = 0; i < a.length; i++) {
            float v = Math.max(-1f, Math.min(1f, a[i]));
            out[i] = (short) Math.round(v * 32767f);
        }
        return out;
    }

    // ------------------------------------------------------------ keyboard sounds
    /** White noise through a one-pole HIGH-pass (higher lp = higher cutoff) with exponential decay. */
    static float[] hpNoise(double sec, double decay, double lp, Random r) {
        float[] out = zeros(sec);
        double y = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE;
            double x = r.nextDouble() * 2 - 1;
            y += lp * (x - y);
            out[i] = (float) ((x - y) * Math.exp(-t * decay) * Math.min(1.0, (sec - t) / 0.003));
        }
        return out;
    }

    /**
     * One mechanical-keyboard key stroke: sharp switch click, plastic clack, a short "thock" body that
     * drops in pitch when the key bottoms out, and a tiny tick when the key returns.
     */
    static float[] key(double thockHz, double click, double thock, double release, long seed) {
        Random r = new Random(seed);
        float[] o = zeros(0.14);
        mix(o, hpNoise(0.022, 230, 0.30, r), 0.000, (float) click);
        mix(o, hpNoise(0.030, 150, 0.55, r), 0.004, (float) (click * 0.6));
        mix(o, tone(0.09, thockHz * 1.5, thockHz * 0.7, 0, 0.0015, 48), 0.005, (float) thock);
        mix(o, noise(0.045, 90, 0.18), 0.005, (float) (thock * 0.35));
        mix(o, hpNoise(0.014, 380, 0.45, r), 0.070, (float) release);
        normalize(o, 0.85f);
        return o;
    }

    /** Digit / dot keys: three slightly different strokes so fast typing does not sound robotic. */
    static float[] click(int variant) {
        double[] hz = {200, 184, 216};
        return key(hz[variant % 3], 0.55, 0.9, 0.25, 11 + variant);
    }

    /** Operator keys: a deeper, fuller stroke. */
    static float[] operator(int variant) {
        double[] hz = {150, 138};
        return key(hz[variant % 2], 0.5, 1.0, 0.3, 31 + variant);
    }

    /** C, backspace, %, +/-: lighter, higher keys. */
    static float[] func() { return key(265, 0.7, 0.6, 0.2, 47); }

    /** "=" is the Enter key: a big deep stroke followed by the stabiliser rattle. */
    static float[] equals() {
        float[] o = zeros(0.26);
        mix(o, key(112, 0.5, 1.15, 0.35, 61), 0.00, 1f);
        mix(o, key(330, 0.45, 0.25, 0.1, 62), 0.035, 0.45f);
        mix(o, hpNoise(0.02, 200, 0.5, new Random(63)), 0.12, 0.25f);
        normalize(o, 0.9f);
        return o;
    }

    /** Key mashing for "ERROR": four quick, uneven strokes. */
    static float[] error() {
        float[] o = zeros(0.5);
        double[] at = {0.0, 0.075, 0.14, 0.23};
        double[] hz = {170, 230, 140, 100};
        for (int i = 0; i < at.length; i++) mix(o, key(hz[i], 0.6, 0.95, 0.15, 71 + i), at[i], 1f);
        normalize(o, 0.9f);
        return o;
    }

    /** Sound of the "C" digit-killing scene. Cuts happen between 0.85 s and 1.40 s. */
    static float[] slice() {
        float[] o = zeros(2.7);
        // panic yelps
        for (int k = 0; k < 6; k++) {
            mix(o, tone(0.1, 500 + 60 * k, 900 + 100 * k, 3, 0.004, 14), 0.05 + 0.13 * k, 0.35f);
        }
        mix(o, ufo(0.5, 700, 220, 13, 3, 2), 0.35, 0.18f);
        // seven blade cuts
        for (int k = 0; k < 7; k++) {
            double at = 0.85 + 0.55 * k / 6.0;
            mix(o, swoosh(0.12, 7000, 1500), at - 0.06, 0.5f);
            mix(o, tone(0.12, 3800, 5200, 0, 0.001, 25), at, 0.3f);
            mix(o, tone(0.1, 180, 70, 0, 0.001, 30), at, 0.5f);
        }
        // comic boing + bwomp + falling sparkles
        mix(o, boing(0.7), 1.5, 0.55f);
        mix(o, tone(0.5, 150, 50, 0, 0.003, 6), 1.6, 0.5f);
        for (int k = 0; k < 6; k++) {
            mix(o, tone(0.07, 1900 - 220 * k, 1500 - 220 * k, 0, 0.002, 30), 1.9 + 0.08 * k, 0.22f);
        }
        saturate(o, 1.3);
        normalize(o, 0.9f);
        return o;
    }

    /** Sound of the "ELIMINATED!" scene; the hit lands at 0.9 s. */
    static float[] eliminate() {
        float[] o = zeros(2.8);
        // riser
        float[] riser = swoosh(0.9, 250, 6500);
        ramp(riser);
        mix(o, riser, 0, 0.7f);
        float[] drone = lowpass(tone(0.9, 55, 120, 2, 0.05, 0), 0.08);
        ramp(drone);
        mix(o, drone, 0, 1.4f);
        // slice zip + impact
        mix(o, swoosh(0.18, 9000, 800), 0.8, 0.7f);
        float[] sub = tone(1.7, 110, 34, 0, 0.001, 1.5);
        saturate(sub, 3);
        mix(o, sub, 0.9, 1.0f);
        mix(o, noise(0.3, 12, 0.9), 0.9, 0.8f);
        mix(o, lowpass(tone(1.0, 220, 214, 2, 0.002, 3.5), 0.25), 0.9, 0.9f);
        mix(o, lowpass(tone(1.0, 330, 322, 2, 0.002, 3.5), 0.25), 0.9, 0.7f);
        mix(o, lowpass(tone(1.0, 277, 271, 2, 0.002, 3.5), 0.25), 0.9, 0.7f);
        // alarm + rumble tail
        mix(o, ufo(1.6, 700, 260, 3, 3, 0.6), 1.0, 0.2f);
        mix(o, lowpass(noise(1.6, 1.4, 1.0), 0.04), 1.0, 2.2f);
        saturate(o, 1.6);
        normalize(o, 0.95f);
        return o;
    }

    // ------------------------------------------------------------ music
    /** 8-second seamless loop, 120 bpm, Am - F - C - G, heavy bass, kick, claps, hats, echoing arpeggio. */
    static float[] music() {
        final double bar = 2.0;
        float[] mix = zeros(8.0);
        float[] arp = zeros(8.0);

        double[] roots = {55.0, 43.65, 65.41, 49.0};
        double[][] arpNotes = {
                {440, 523.25, 659.25, 880},
                {349.23, 440, 523.25, 698.46},
                {523.25, 659.25, 783.99, 1046.5},
                {392, 493.88, 587.33, 783.99}
        };
        int[] arpPattern = {0, 1, 2, 3, 2, 1, 0, 1, 2, 3, 2, 1, 3, 2, 1, 0};
        double[] bassAt = {0.0, 0.75, 1.0, 1.5};
        double[] bassMul = {1, 1, 1, 2};

        for (int b = 0; b < 4; b++) {
            double t0 = b * bar;
            double root = roots[b];

            for (int k = 0; k < bassAt.length; k++) {
                double f = root * bassMul[k];
                float[] saw = lowpass(tone(0.36, f, f, 2, 0.004, 5), 0.10);
                float[] sine = tone(0.36, f, f, 0, 0.004, 4);
                mix(mix, saw, t0 + bassAt[k], 0.9f);
                mix(mix, sine, t0 + bassAt[k], 0.8f);
            }
            // pad
            mix(mix, tone(2.0, root * 4, root * 4, 3, 0.4, 0.25), t0, 0.07f);
            mix(mix, tone(2.0, root * 4 * 1.2, root * 4 * 1.2, 3, 0.4, 0.25), t0, 0.05f);

            for (int beat = 0; beat < 4; beat++) {
                double tb = t0 + beat * 0.5;
                mix(mix, tone(0.3, 140, 42, 0, 0.001, 11), tb, 1.0f);          // kick
                mix(mix, noise(0.012, 0, 1.0), tb, 0.35f);                      // click
                mix(mix, hat(0.05, 70), tb + 0.25, 0.22f);                      // off-beat hat
                if (beat == 1 || beat == 3) mix(mix, noise(0.16, 18, 0.55), tb, 0.35f); // clap
            }
            for (int s = 0; s < 16; s++) {
                double ts = t0 + s * 0.125;
                double f = arpNotes[b][arpPattern[s]];
                mix(arp, tone(0.11, f, f, 1, 0.002, 16), ts, 0.10f);
            }
        }
        // echo for the arpeggio (wraps around so the loop is seamless)
        int delay = (int) Math.round(0.375 * RATE);
        float[] echoed = arp.clone();
        for (int rep = 1; rep <= 3; rep++) {
            float g = (float) Math.pow(0.42, rep);
            for (int i = 0; i < arp.length; i++) {
                echoed[(i + delay * rep) % arp.length] += arp[i] * g;
            }
        }
        for (int i = 0; i < mix.length; i++) mix[i] += echoed[i];

        saturate(mix, 1.5);
        normalize(mix, 0.9f);
        return mix;
    }
}
