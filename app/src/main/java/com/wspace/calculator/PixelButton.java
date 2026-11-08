package com.wspace.calculator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Chunky pixel-art key: stepped corners, light top/left bevel, dark bottom/right bevel. */
final class PixelButton extends View {

    static final int DIGIT = 0, OPERATOR = 1, FUNCTION = 2, EQUALS = 3, ICON = 4;

    static final String ICON_BACK = "⌫";
    static final String ICON_SOUND = "SND";

    private final String label;
    private final int kind;
    private final Paint p = new Paint();
    private boolean down = false;
    private boolean muted = false;
    private final int edge = 0xFF05050F;
    private final int face, light, dark, text, textShadow;

    PixelButton(Context ctx, String label, int kind) {
        super(ctx);
        this.label = label;
        this.kind = kind;
        textShadow = 0x99000010;
        switch (kind) {
            case OPERATOR:
                face = 0xFF1F8C87; light = 0xFF4FD6C8; dark = 0xFF0F5A5A; text = 0xFFEFFFFC; break;
            case FUNCTION:
                face = 0xFF8A3C70; light = 0xFFC668A6; dark = 0xFF55224A; text = 0xFFFFF0FA; break;
            case EQUALS:
                face = 0xFF5FAE3E; light = 0xFF98E06A; dark = 0xFF357A24; text = 0xFFF6FFE8; break;
            case ICON:
                face = 0xFF2B3470; light = 0xFF5662B8; dark = 0xFF182048; text = 0xFFEAF0FF; break;
            default:
                face = 0xFF2D3A78; light = 0xFF5568BE; dark = 0xFF1A2350; text = 0xFFEAF0FF; break;
        }
        setClickable(true);
    }

    void setMuted(boolean m) { muted = m; invalidate(); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                down = false;
                invalidate();
                if (e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight()) {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                down = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** Rectangle with the corners cut away in two pixel steps. */
    private void stepped(Canvas c, float l, float t, float r, float b, float u, int color) {
        p.setColor(color);
        c.drawRect(l + 2 * u, t, r - 2 * u, b, p);
        c.drawRect(l + u, t + u, r - u, b - u, p);
        c.drawRect(l, t + 2 * u, r, b - 2 * u, p);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float u = Math.max(2f, Math.round(Math.min(w, h) / 24f));
        float gap = u;                               // breathing room between neighbouring keys
        float l = gap, t = gap, r = w - gap, b = h - gap;
        p.setStyle(Paint.Style.FILL);

        float dy = down ? u : 0f;                    // pressed keys sink one pixel
        t += dy; b += dy;
        if (!down) {                                 // hard drop-shadow under the key
            stepped(c, l, t + u, r, b + u, u, 0x66000000);
        }
        stepped(c, l, t, r, b, u, edge);
        stepped(c, l + u, t + u, r - u, b - u, u, down ? dark : face);
        if (!down) {
            // bevel: light on top/left, dark on bottom/right
            p.setColor(light);
            c.drawRect(l + 3 * u, t + u, r - 3 * u, t + 2 * u, p);
            c.drawRect(l + u, t + 3 * u, l + 2 * u, b - 3 * u, p);
            p.setColor(dark);
            c.drawRect(l + 3 * u, b - 2 * u, r - 3 * u, b - u, p);
            c.drawRect(r - 2 * u, t + 3 * u, r - u, b - 3 * u, p);
        } else {
            p.setColor(face);
            c.drawRect(l + 3 * u, t + 3 * u, r - 3 * u, b - 3 * u, p);
        }

        float cx = w / 2f, cy = (t + b) / 2f;
        if (ICON_BACK.equals(label)) {
            drawBack(c, cx, cy, Math.max(2f, Math.round(Math.min(w, h) / 16f)));
        } else if (ICON_SOUND.equals(label)) {
            drawSpeaker(c, cx, cy, Math.max(2f, Math.round(Math.min(w, h) / 13f)));
        } else {
            // glyph scale: big for single symbols, smaller for longer labels
            float lu = Math.max(2f, Math.round(Math.min(w, h) * (label.length() > 1 ? 0.40f : 0.50f) / FontData.HEIGHT));
            float tw = FontData.textWidth(label) * lu;
            PixelFont.shadowed(c, p, label, Math.round(cx - tw / 2f), Math.round(cy - FontData.HEIGHT * lu / 2f),
                    lu, text, textShadow);
        }
    }

    // 12 x 7 "backspace" key: '#' = key body, 'x' = the cross cut out of it
    private static final String[] BACK = {
            "...#########",
            "..##########",
            ".#####x#x###",
            "#######x####",
            ".#####x#x###",
            "..##########",
            "...#########"};

    private void drawBack(Canvas c, float cx, float cy, float u) {
        float x0 = Math.round(cx - 6 * u), y0 = Math.round(cy - 3.5f * u);
        p.setStyle(Paint.Style.FILL);
        for (int r = 0; r < BACK.length; r++) {
            for (int col = 0; col < BACK[r].length(); col++) {
                char ch = BACK[r].charAt(col);
                if (ch == '.') continue;
                p.setColor(ch == '#' ? 0xFFFFF0FA : face);
                c.drawRect(x0 + col * u, y0 + r * u, x0 + (col + 1) * u, y0 + (r + 1) * u, p);
            }
        }
    }

    // 11 x 9 speaker icon with waves (or a red X when muted)
    private void drawSpeaker(Canvas c, float cx, float cy, float u) {
        String[] rows = {
                "...#.......",
                "..##...#...",
                ".###....#..",
                "####.#...#.",
                "####..#..#.",
                "####.#...#.",
                ".###....#..",
                "..##...#...",
                "...#......."};
        float w = 11 * u, h = 9 * u;
        float x0 = Math.round(cx - w / 2f), y0 = Math.round(cy - h / 2f);
        p.setStyle(Paint.Style.FILL);
        for (int r = 0; r < rows.length; r++) {
            for (int col = 0; col < rows[r].length(); col++) {
                if (rows[r].charAt(col) != '#') continue;
                boolean wave = col >= 5;
                if (wave && muted) continue;
                p.setColor(wave ? 0xFF7DF9FF : 0xFFEAF0FF);
                c.drawRect(x0 + col * u, y0 + r * u, x0 + (col + 1) * u, y0 + (r + 1) * u, p);
            }
        }
        if (muted) {
            p.setColor(0xFFFF5C7A);
            for (int i = 0; i < 5; i++) {
                c.drawRect(x0 + (6 + i) * u, y0 + (2 + i) * u, x0 + (7 + i) * u, y0 + (3 + i) * u, p);
                c.drawRect(x0 + (6 + i) * u, y0 + (6 - i) * u, x0 + (7 + i) * u, y0 + (7 - i) * u, p);
            }
        }
    }
}
