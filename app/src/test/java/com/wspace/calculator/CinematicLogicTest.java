package com.wspace.calculator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import org.junit.Test;

/** Timing, 106 trigger and audio-file checks for the cinematic features. */
public class CinematicLogicTest {

    private static WavData wav(String name) throws IOException {
        try (InputStream in = new FileInputStream("src/main/res/raw/" + name + ".wav")) { return WavData.read(in); }
    }

    // ---- three-operation rule
    @Test public void sceneOnlyForThreeOperations() {
        assertEquals(1, Calc.countOperations("5+5"));
        assertEquals(1, Calc.countOperations("10*2"));
        assertEquals(3, Calc.countOperations("10+20*3-5"));
        assertEquals("65", Calc.evaluateToString("10+20*3-5"));
    }

    // ---- typewriter
    @Test public void phraseIsTypedOnlyDuringBursts() {
        assertEquals(35, ScenePlan.phraseLength());
        assertEquals(0, ScenePlan.revealedChars(0.0f));
        assertEquals(0, ScenePlan.revealedChars(0.04f));
        assertTrue(ScenePlan.revealedChars(0.06f) >= 1);
        int before = ScenePlan.revealedChars(0.15f), gap = ScenePlan.revealedChars(0.18f);
        assertEquals(before, gap);                       // silence between bursts: nothing is typed
        assertEquals(35, ScenePlan.revealedChars(ScenePlan.TRRR_LEN));
        int prev = 0;
        for (float t = 0; t < 2.1f; t += 0.005f) {
            int n = ScenePlan.revealedChars(t);
            assertTrue(n >= prev && n - prev <= 1);       // monotonic, one letter at a time
            prev = n;
        }
    }

    @Test public void perLineSplit() {
        assertEquals(3, ScenePlan.perLine(3)[0] + 0 * 1);
        int[] all = ScenePlan.perLine(35);
        assertEquals(15, all[0]); assertEquals(12, all[1]); assertEquals(8, all[2]);
        assertEquals(0, ScenePlan.perLine(15)[1]);
    }

    @Test public void burstTableMatchesTheRealFile() throws IOException {
        WavData w = wav("sfx_trrr");
        assertEquals(ScenePlan.TRRR_LEN, w.seconds(), 0.05);
        double in = 0, out = 0; int nin = 0, nout = 0;
        for (int i = 0; i < w.pcm.length; i++) {
            float t = i / (float) w.rate;
            boolean inside = false;
            for (int b = 0; b < ScenePlan.TRRR_ON.length; b++) if (t >= ScenePlan.TRRR_ON[b] && t < ScenePlan.TRRR_OFF[b]) inside = true;
            double v = Math.abs(w.pcm[i] / 32768.0);
            if (inside) { in += v; nin++; } else { out += v; nout++; }
        }
        assertTrue("bursts must be much louder than gaps", in / nin > 8 * (out / nout));
    }

    @Test public void killHitMatchesTheRealFile() throws IOException {
        WavData w = wav("sfx_kill");
        assertEquals(ScenePlan.KILL_LEN, w.seconds(), 0.05);
        int first = 0;
        while (first < w.pcm.length && Math.abs(w.pcm[first]) < 0.03 * 32768) first++;
        assertEquals(ScenePlan.KILL_IMPACT, first / (double) w.rate, 0.02);
    }

    @Test public void soundFilesLoad() throws IOException {
        for (String n : new String[]{"sfx_106", "key_digit0", "key_digit3", "key_op0", "key_op1", "key_equals"}) {
            WavData w = wav(n);
            assertEquals(44100, w.rate);
            assertEquals(1, w.channels);
            assertTrue(n, w.seconds() > 0.03);
        }
        assertEquals(2.9, wav("sfx_106").seconds(), 0.05);
    }

    // ---- clock
    @Test public void clockFollowsAudioAndNeverStops() {
        ScenePlan.Clock c = new ScenePlan.Clock(1.15f);
        assertEquals(0f, c.update(0.1f, -1f, 0.016f), 1e-6);        // waiting for audio to start
        assertEquals(0.2f, c.update(0.3f, 0.2f, 0.016f), 1e-6);     // follows the playhead
        assertEquals(0.2f, c.update(0.3f, 0.19f, 0.016f), 1e-6);    // never backwards
        float t = c.time();
        for (int i = 0; i < 100; i++) t = c.update(2f, 1.149f, 0.016f);   // audio ended: keeps counting
        assertTrue(t > 2.0f);
        ScenePlan.Clock none = new ScenePlan.Clock(1f);                     // no audio at all
        float x = 0;
        for (int i = 0; i < 200; i++) x = none.update(i * 0.016f, -1f, 0.016f);
        assertTrue(x > 2f);
    }

    // ---- 106
    @Test public void trigger106Rules() {
        Trigger106 t = new Trigger106();
        assertEquals(Trigger106.Action.NONE, t.update("1"));
        assertEquals(Trigger106.Action.NONE, t.update("10"));
        assertEquals(Trigger106.Action.ARM, t.update("106"));        // typed directly
        assertEquals(Trigger106.Action.NONE, t.update("106"));       // same 106 never re-arms
        assertEquals(Trigger106.Action.CANCEL, t.update("10"));      // erased before 2 s
        assertEquals(Trigger106.Action.ARM, t.update("106"));
        assertEquals(Trigger106.Action.CANCEL, t.update(""));        // cleared
        assertEquals(Trigger106.Action.NONE, t.update("1060"));
        assertEquals(Trigger106.Action.NONE, t.update("105"));
        assertEquals(Trigger106.Action.ARM, t.update(Calc.evaluateToString("100+3*2")));   // result 106
        assertEquals(Trigger106.Action.CANCEL, t.update("5"));       // new typing
        assertEquals(Trigger106.Action.NONE, t.update(Calc.evaluateToString("53*2.0001")));  // 106.0053: not 106
    }
}
