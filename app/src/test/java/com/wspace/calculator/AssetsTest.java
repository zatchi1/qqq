package com.wspace.calculator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Sanity checks for the pure-Java pixel font, sprite data and synthesised sounds. */
public class AssetsTest {

    @Test public void everyGlyphIsWellFormed() {
        String all = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ.!+-=% ×÷±";
        for (char ch : all.toCharArray()) {
            assertTrue("missing glyph " + ch, FontData.has(ch));
            String[] g = FontData.glyph(ch);
            assertEquals("rows of " + ch, FontData.HEIGHT, g.length);
            for (String row : g) {
                assertEquals("row width of " + ch, g[0].length(), row.length());
                for (char c : row.toCharArray()) assertTrue(c == '#' || c == '.');
            }
        }
    }

    @Test public void everyDisplayedCharacterHasAGlyph() {
        String shown = Calc.toDisplay("-1234567890.+*/%") + "ERROR" + "1E15" + "ELIMINATED!" + "TOO MANY OPERATIONS"
                + "WASHWASHNI" + "±";
        for (char ch : shown.toCharArray()) assertTrue("no glyph for " + ch, FontData.has(ch));
    }

    @Test public void textWidthCountsSpacing() {
        assertEquals(0, FontData.textWidth(""));
        assertEquals(5, FontData.textWidth("0"));
        assertEquals(11, FontData.textWidth("00"));
        assertEquals(8, FontData.textWidth("0."));   // 5 + 1 + 2
    }

    @Test public void spriteIsRectangularAndPaletteComplete() {
        assertEquals(SpriteData.H, SpriteData.ROWS.length);
        int opaque = 0;
        for (String row : SpriteData.ROWS) {
            assertEquals(SpriteData.W, row.length());
            for (char ch : row.toCharArray()) {
                if (ch == '.') continue;
                opaque++;
                assertTrue("no colour for '" + ch + "'", SpriteData.color(ch) != 0);
            }
        }
        assertTrue(opaque > 200);
    }

    private static void assertSound(String name, float[] a, double minSec, double maxSec) {
        double sec = a.length / (double) Synth.RATE;
        assertTrue(name + " length " + sec, sec >= minSec && sec <= maxSec);
        float peak = 0;
        for (float v : a) {
            assertTrue(name + " has NaN", !Float.isNaN(v) && !Float.isInfinite(v));
            peak = Math.max(peak, Math.abs(v));
        }
        assertTrue(name + " is silent", peak > 0.2f);
        assertTrue(name + " clips", peak <= 1.0001f);
    }

    @Test public void keyboardSoundsAreValid() {
        for (int i = 0; i < 3; i++) assertSound("click" + i, Synth.click(i), 0.1, 0.2);
        for (int i = 0; i < 2; i++) assertSound("op" + i, Synth.operator(i), 0.1, 0.2);
        assertSound("func", Synth.func(), 0.1, 0.2);
        assertSound("equals", Synth.equals(), 0.2, 0.4);
        assertSound("error", Synth.error(), 0.4, 0.6);
    }

    @Test public void sceneSoundsAreValid() {
        assertSound("slice", Synth.slice(), 2.6, 2.8);
        assertSound("eliminate", Synth.eliminate(), 2.7, 2.9);
        assertSound("music", Synth.music(), 7.9, 8.1);
    }
}
