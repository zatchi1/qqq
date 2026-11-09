package com.wspace.calculator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
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
    private long fxStart = -1;                       // when the last press effect began
    private final float[] fxAng = new float[6];      // flying sparkle directions
    private final float[] fxDist = new float[6];
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
                startFx();
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

    private static final float FX_SECONDS = 0.38f;

    /** Starts the small press effect: a soft flash on the key and a few pixel sparkles flying out. */
    private void startFx() {
        fxStart = SystemClock.uptimeMillis();
        for (int i = 0; i < fxAng.length; i++) {
            fxAng[i] = (float) ((i + Math.random() * 0.8) * Math.PI * 2 / fxAng.length);
            fxDist[i] = 0.7f + (float) Math.random() * 0.3f;
        }
    }

    private static int withAlpha(int color, float a) {
        int al = Math.max(0, Math.min(255, Math.round(a * 255f)));
        return (al << 24) | (color & 0x00FFFFFF);
    }

    /** Press effect drawn on top of the key; keeps the view redrawing only while it is visible. */
    private void drawFx(Canvas c, float w, float h, float u) {
        if (fxStart < 0) return;
        float age = (SystemClock.uptimeMillis() - fxStart) / 1000f;
        if (age >= FX_SECONDS) {
            fxStart = -1;
            return;
        }
        float k = age / FX_SECONDS;                          // 0..1
        float ease = 1f - (1f - k) * (1f - k);
        p.setStyle(Paint.Style.FILL);

        // soft flash that melts away
        float gap = u;
        float flash = Math.max(0f, 1f - k * 2.6f);
        if (flash > 0f) {
            stepped(c, gap + 3 * u, gap + 3 * u, w - gap - 3 * u, h - gap - 3 * u, u, withAlpha(0xFFFFFFFF, 0.28f * flash));
        }
        // sparkles
        float cx = w / 2f, cy = h / 2f;
        float reach = Math.min(w, h) * 0.46f;
        float size = Math.max(2f, u * (1.6f - 1.1f * k));
        for (int i = 0; i < fxAng.length; i++) {
            float d = reach * fxDist[i] * (0.25f + 0.75f * ease);
            float x = Math.round((cx + (float) Math.cos(fxAng[i]) * d * (w / Math.min(w, h))) / u) * u;
            float y = Math.round((cy + (float) Math.sin(fxAng[i]) * d) / u) * u;
            p.setColor(withAlpha(i % 2 == 0 ? 0xFFFFFFFF : light, 1f - k));
            c.drawRect(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f, p);
        }
        postInvalidateOnAnimation();
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
        drawFx(c, w, h, u);
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
