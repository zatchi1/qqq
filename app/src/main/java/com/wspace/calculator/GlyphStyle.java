package com.wspace.calculator;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Cartoon-space lettering: rounded black font, vertical gradient fill, dark outline and a soft glow
 * made of wider translucent strokes (cheap and works with hardware acceleration).
 */
final class GlyphStyle {

    static final Typeface FONT = Typeface.create("sans-serif-black", Typeface.NORMAL);

    final int top, bottom, outline, glow;

    GlyphStyle(int top, int bottom, int outline, int glow) {
        this.top = top; this.bottom = bottom; this.outline = outline; this.glow = glow;
    }

    /** Turquoise-to-green futuristic digits. */
    static final GlyphStyle NEON = new GlyphStyle(0xFFEFFFF8, 0xFF2EF2C2, 0xFF04443F, 0xFF18F5C8);
    /** Little green alien digits (used by the "C" kill scene). */
    static final GlyphStyle ALIEN = new GlyphStyle(0xFFC8FF95, 0xFF3CC85A, 0xFF0B4A22, 0xFF39FF88);
    /** Button labels. */
    static final GlyphStyle WHITE = new GlyphStyle(0xFFFFFFFF, 0xFFBFEFFF, 0xFF063247, 0xFF5CD8FF);

    /** Prepares paint for glyph drawing: call once per paint. */
    static void setup(Paint p) {
        p.setAntiAlias(true);
        p.setTypeface(FONT);
        p.setTextAlign(Paint.Align.CENTER);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Draws text centred horizontally on x with its baseline at y. */
    void draw(Canvas c, Paint p, String s, float x, float y, float size, float glowAmount) {
        p.setTextSize(size);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        if (glowAmount > 0f) {
            p.setColor(glow);
            p.setAlpha((int) (46 * glowAmount));
            p.setStrokeWidth(size * 0.32f);
            c.drawText(s, x, y, p);
            p.setColor(glow);
            p.setAlpha((int) (82 * glowAmount));
            p.setStrokeWidth(size * 0.19f);
            c.drawText(s, x, y, p);
        }
        p.setColor(outline);
        p.setAlpha(255);
        p.setStrokeWidth(size * 0.09f);
        c.drawText(s, x, y, p);

        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(x, y - size * 0.78f, x, y, top, bottom, Shader.TileMode.CLAMP));
        c.drawText(s, x, y, p);
        p.setShader(null);
    }
}
