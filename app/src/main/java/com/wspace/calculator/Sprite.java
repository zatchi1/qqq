package com.wspace.calculator;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

/** Draws the character from {@link SpriteData} as solid blocks (run-length per row). */
final class Sprite {
    private Sprite() {}

    private static final int FOOT_OUTLINE = 0xFF14141E;
    private static final int FOOT_FILL = 0xFF6FA855;

    /**
     * @param x,y   top-left of the sprite in px
     * @param s     size of one sprite pixel in px
     * @param flip  mirror horizontally (character facing left)
     * @param step  walk-cycle phase 0 / 1 (two feet pixels that alternate) or -1 for no feet
     */
    static void draw(Canvas c, Paint p, float x, float y, float s, boolean flip, int step) {
        c.save();
        if (flip) {
            c.translate(x + SpriteData.W * s, y);
            c.scale(-1f, 1f);
        } else {
            c.translate(x, y);
        }
        p.setStyle(Paint.Style.FILL);
        if (step >= 0) drawFeet(c, p, s, step);
        for (int r = 0; r < SpriteData.H; r++) {
            String row = SpriteData.ROWS[r];
            int start = 0;
            while (start < row.length()) {
                char ch = row.charAt(start);
                int end = start + 1;
                while (end < row.length() && row.charAt(end) == ch) end++;
                if (ch != '.') {
                    p.setColor(SpriteData.color(ch));
                    c.drawRect(start * s, r * s, end * s, (r + 1) * s, p);
                }
                start = end;
            }
        }
        c.restore();
    }

    /** Two tiny feet below the body; they swap lift every step. Drawn from y = H*s downwards. */
    private static void drawFeet(Canvas c, Paint p, float s, int step) {
        float base = (SpriteData.H - 1) * s;           // overlaps the last outline row
        for (int i = 0; i < 2; i++) {
            boolean lifted = (i == step);
            float fx = (i == 0 ? 5f : 13f) * s;
            float fy = base + (lifted ? 0f : 1f) * s;
            p.setColor(FOOT_OUTLINE);
            c.drawRect(fx - s, fy, fx + 3f * s, fy + 2f * s, p);
            p.setColor(FOOT_FILL);
            c.drawRect(fx, fy, fx + 2f * s, fy + 1f * s, p);
        }
    }

    /** Renders the body (no feet) into a bitmap where every sprite pixel is s x s px. */
    static Bitmap render(Paint p, int s) {
        Bitmap b = Bitmap.createBitmap(SpriteData.W * s, SpriteData.H * s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        draw(c, p, 0f, 0f, s, false, -1);
        return b;
    }
}
