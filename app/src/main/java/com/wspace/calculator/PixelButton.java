package com.wspace.calculator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** Chunky pixel-art key: stepped corners, light top/left bevel, dark bottom/right bevel. */
final class PixelButton extends View {

    static final int DIGIT = 0, OPERATOR = 1, FUNCTION = 2, EQUALS = 3, ICON = 4, CLEAR = 5, SIGN = 6, ICON_LIME = 7;

    static final String ICON_BACK = "⌫";
    static final String ICON_SOUND = "SND";
    static final String ICON_GEAR = "GEAR";

    private final String label;
    private final int kind;
    private final Paint p = new Paint();
    private boolean down = false;
    private boolean muted = false;
    private long fxStart = -1;                       // when the last press effect began
    private final float[] fxAng = new float[6];      // flying sparkle directions
    private final float[] fxDist = new float[6];
    private final int edge;
    private final int face, light, dark, text, textShadow;

    PixelButton(Context ctx, String label, int kind) {
        super(ctx);
        this.label = label;
        this.kind = kind;
        textShadow = 0x99000010;
        int e = 0xFF05050F;
        switch (kind) {
            case OPERATOR:
                face = 0xFF1FB254; light = 0xFF6BF095; dark = 0xFF0F7A38; text = 0xFFFFFFFF; break;
            case FUNCTION:
                face = 0xFF7B2FB5; light = 0xFFB766F0; dark = 0xFF4E1C78; text = 0xFFFFFFFF; break;
            case CLEAR:
                face = 0xFFE02828; light = 0xFFFF7A66; dark = 0xFF9A1414; text = 0xFFFFFFFF; break;
            case SIGN:
                face = 0xFFB02A9C; light = 0xFFE866D2; dark = 0xFF761A68; text = 0xFFFFFFFF; break;
            case EQUALS:
                face = 0xFF22C84A; light = 0xFF9CFF86; dark = 0xFF14872F; text = 0xFFFFFFFF; break;
            case ICON:
                face = 0xFF0E1A4A; light = 0xFF2A3E8E; dark = 0xFF08102E; text = 0xFFEAF0FF; e = 0xFF39D6FF; break;
            case ICON_LIME:
                face = 0xFF0E2A1E; light = 0xFF1E5A3A; dark = 0xFF08180F; text = 0xFFEAF0FF; e = 0xFF55E83A; break;
            default:
                face = 0xFF2A44B4; light = 0xFF6B86F0; dark = 0xFF182A7A; text = 0xFFFFFFFF; break;
        }
        edge = e;
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

        if (kind == CLEAR) {                          // yellow/black hazard stripe along the bottom edge
            float sy = b - 4 * u, sx0 = l + 3 * u, sx1 = r - 3 * u;
            for (float x = sx0, i = 0; x < sx1; x += 2 * u, i++) {
                p.setColor(((int) i) % 2 == 0 ? 0xFFFFD21E : 0xFF1A1A1A);
                c.drawRect(x, sy, Math.min(x + 2 * u, sx1), sy + 2 * u, p);
            }
        }
        if (kind == EQUALS && !down) {                // pulsing bright rim so "=" glows
            float pulse = 0.5f + 0.5f * (float) Math.sin(SystemClock.uptimeMillis() / 380.0);
            p.setColor(withAlpha(0xFFE8FFB0, 0.35f + 0.55f * pulse));
            c.drawRect(l + 3 * u, t + u, r - 3 * u, t + 2 * u, p);
            c.drawRect(l + 3 * u, b - 2 * u, r - 3 * u, b - u, p);
            c.drawRect(l + u, t + 3 * u, l + 2 * u, b - 3 * u, p);
            c.drawRect(r - 2 * u, t + 3 * u, r - u, b - 3 * u, p);
            postInvalidateDelayed(60);
        }
        float cx = w / 2f, cy = (t + b) / 2f;
        if (ICON_GEAR.equals(label)) {
            drawGear(c, cx, cy, Math.max(2f, Math.round(Math.min(w, h) / 14f)));
        } else if (ICON_BACK.equals(label)) {
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

    /** Pixel gear built from a circle: ring + 8 teeth. */
    private void drawGear(Canvas c, float cx, float cy, float u) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF8DFFB0);
        for (int dy = -5; dy <= 5; dy++) {
            for (int dx = -5; dx <= 5; dx++) {
                double r = Math.hypot(dx, dy);
                if (r < 2.1) continue;
                boolean body = r <= 3.9;
                boolean tooth = r <= 5.4 && (Math.abs(dx) <= 1 || Math.abs(dy) <= 1 || Math.abs(Math.abs(dx) - Math.abs(dy)) <= 1);
                if (body || tooth) c.drawRect(cx + (dx - 0.5f) * u, cy + (dy - 0.5f) * u, cx + (dx + 0.5f) * u, cy + (dy + 0.5f) * u, p);
            }
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
                p.setColor(wave ? 0xFF59E0FF : 0xFFEAF0FF);
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
