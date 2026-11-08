package com.wspace.calculator;

import android.graphics.Canvas;
import android.graphics.Paint;

/** Draws {@link FontData} text as crisp square blocks (no anti-aliasing artefacts, scales to any size). */
final class PixelFont {
    private PixelFont() {}

    /** Draws one glyph with its top-left at (x, y); u = size of one font pixel in px. */
    static void glyph(Canvas c, Paint p, char ch, float x, float y, float u, int color) {
        String[] g = FontData.glyph(ch);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        for (int r = 0; r < g.length; r++) {
            String row = g[r];
            int run = -1;
            for (int col = 0; col <= row.length(); col++) {
                boolean on = col < row.length() && row.charAt(col) == '#';
                if (on && run < 0) run = col;
                if (!on && run >= 0) {
                    c.drawRect(x + run * u, y + r * u, x + col * u, y + (r + 1) * u, p);
                    run = -1;
                }
            }
        }
    }

    /** Draws a string with its top-left at (x, y). */
    static void text(Canvas c, Paint p, String s, float x, float y, float u, int color) {
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            glyph(c, p, ch, cx, y, u, color);
            cx += (FontData.width(ch) + 1) * u;
        }
    }

    /** Text with a hard drop shadow. */
    static void shadowed(Canvas c, Paint p, String s, float x, float y, float u, int color, int shadow) {
        float off = Math.max(1f, Math.round(u * 0.5f));
        text(c, p, s, x + off, y + off, u, shadow);
        text(c, p, s, x, y, u, color);
    }

    /** Text with a solid 1-pixel outline all around (classic sprite-font look) plus the fill colour. */
    static void outlined(Canvas c, Paint p, String s, float x, float y, float u, int color, int outline) {
        float o = Math.max(1f, Math.round(u * 0.6f));
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dy != 0) text(c, p, s, x + dx * o, y + dy * o, u, outline);
            }
        }
        text(c, p, s, x, y, u, color);
    }

    static float width(String s, float u) { return FontData.textWidth(s) * u; }

    static float height(float u) { return FontData.HEIGHT * u; }
}
