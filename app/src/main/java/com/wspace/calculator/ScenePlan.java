package com.wspace.calculator;

/**
 * Pure-Java timing tables that keep pictures, typing and sound locked together (unit-tested).
 *
 * All numbers were measured from the uploaded audio (see tools/make_assets.py), so the animation
 * follows what is really in the files:
 *  - T R R R: 22 short bursts; the sentence is typed ONLY while a burst is audible.
 *  - KILL:    the hit lands about 0.06 s after the file starts.
 */
final class ScenePlan {
    private ScenePlan() {}

    /** Shown during the floating-character scene, split in lines (no space is lost at the breaks). */
    static final String[] PHRASE_LINES = {"YOU GOT EXCITED", "PRESSING THE", "BUTTONS!"};

    /** Burst start / end times (seconds) inside sfx_trrr.wav. */
    static final float[] TRRR_ON = {
            0.048f, 0.104f, 0.188f, 0.232f, 0.316f, 0.468f, 0.584f, 0.668f, 0.872f, 0.936f, 1.012f,
            1.140f, 1.204f, 1.288f, 1.416f, 1.492f, 1.556f, 1.644f, 1.708f, 1.760f, 1.836f, 1.908f};
    static final float[] TRRR_OFF = {
            0.096f, 0.148f, 0.228f, 0.276f, 0.364f, 0.512f, 0.632f, 0.760f, 0.920f, 0.984f, 1.060f,
            1.188f, 1.252f, 1.336f, 1.464f, 1.540f, 1.604f, 1.688f, 1.752f, 1.808f, 1.880f, 1.956f};

    /** Length of sfx_trrr.wav, sfx_kill.wav and the moment the KILL hit is heard. */
    static final float TRRR_LEN = 2.02f;
    static final float KILL_LEN = 1.15f;
    static final float KILL_IMPACT = 0.06f;

    /** If the audio clock never starts (engine not ready), animations fall back to the wall clock. */
    static final float AUDIO_GRACE = 0.45f;

    static int phraseLength() {
        int n = 0;
        for (String s : PHRASE_LINES) n += s.length();
        return n;
    }

    /** Seconds of audible TRRR sound heard up to time t. */
    static float activeTime(float t) {
        float a = 0f;
        for (int i = 0; i < TRRR_ON.length; i++) {
            if (t <= TRRR_ON[i]) break;
            a += Math.min(t, TRRR_OFF[i]) - TRRR_ON[i];
        }
        return a;
    }

    static float totalActive() {
        float a = 0f;
        for (int i = 0; i < TRRR_ON.length; i++) a += TRRR_OFF[i] - TRRR_ON[i];
        return a;
    }

    /**
     * How many letters of the phrase are on screen at audio time t (0..phraseLength()).
     * Letters appear only while sound is playing and the last one lands with the last burst.
     */
    static int revealedChars(float t) {
        int total = phraseLength();
        float act = activeTime(t);
        if (act <= 0f) return 0;
        int n = (int) Math.ceil(total * (double) act / totalActive() - 1e-6);
        return Math.max(0, Math.min(total, n));
    }

    /** Splits a global revealed-letter count into the number of letters visible on each line. */
    static int[] perLine(int revealed) {
        int[] out = new int[PHRASE_LINES.length];
        int left = revealed;
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.max(0, Math.min(PHRASE_LINES[i].length(), left));
            left -= out[i];
        }
        return out;
    }

    /**
     * One animation clock for a sound. While the audio plays it follows the audio playhead (exact
     * sync, never runs backwards). Once the sound has ended or stalled - or never started because
     * audio is unavailable - it keeps counting with the frame time, so a scene can never hang.
     */
    static final class Clock {
        private final float length;       // length of the sound in seconds
        private float t = 0f;             // current scene time (s)
        private float lastPlayhead = 0f;
        private float stall = 0f;         // how long the playhead has not moved
        private boolean heard = false;    // the playhead has moved at least once

        Clock(float soundLength) { length = soundLength; }

        /**
         * @param wallSince seconds since the sound was started
         * @param playhead  seconds the audio has really played, or a negative value when unknown
         * @param dt        seconds since the previous frame
         * @return seconds since the start of the sound, aligned with what is audible
         */
        float update(float wallSince, float playhead, float dt) {
            if (playhead > 0f && playhead > lastPlayhead + 1e-4f) {
                lastPlayhead = playhead;
                stall = 0f;
                heard = true;
                if (playhead > t) t = playhead;
            } else if (heard) {
                stall += dt;
                if (lastPlayhead >= length - 0.01f || stall > 0.12f) t += dt;     // ended or stalled
            } else if (wallSince > AUDIO_GRACE) {
                t += dt;                                                         // no audio at all
            }
            return t;
        }

        float time() { return t; }
    }
}
