package com.wspace.calculator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/**
 * Pixel-art calculator screen.
 *
 * Normal mode: two lines of pixel digits (expression + big line); every new digit pops in.
 *
 * Kill mode ({@link #startSlice}): every character turns into a tiny green creature (eyes + legs),
 * panics, then is genuinely cut in two. Each glyph is rendered to a bitmap that is split along a
 * line into a TOP bitmap and a BOTTOM bitmap ({@link Slicer}); red flesh and a small white bone are
 * painted on the cut faces and the halves fall away, rotating, with smoke and sparks.
 */
final class DisplayView extends View {

    private static final int NEON = 0xFF8DFFB0, NEON_SHADOW = 0xFF06301C;
    private static final int DIM = 0xFF4FB88C, DIM_SHADOW = 0xFF06241A;
    private static final int ALIEN_FILL = 0xFF92C673, ALIEN_EDGE = 0xFF14141E;

    // timeline of the kill scene (seconds). The KILL sound starts at PRE_ROLL and its hit is heard
    // KILL_IMPACT later: that is exactly when the first digit is cut (T_CUT); the rest follow inside
    // the loud part of the sound, and from then on the animation follows the audio playhead.
    private static final float T_ALIEN = 0.30f;     // digit turns into a creature
    private static final float T_PANIC = 0.30f;     // it notices the danger
    private static final float T_IMPACT = ScenePlan.KILL_IMPACT;
    private static final float PRE_ROLL = 0.55f;    // KILL sound starts here
    private static final float T_CUT = PRE_ROLL + T_IMPACT;   // first cut
    private static final float CUT_SPREAD = 0.14f;  // cuts are spread over this window
    private static final float T_FALL = 0.90f;      // fall + fade after a cut

    private static final class G {
        char ch;
        float left, top, u;
        int gw;                         // glyph width in font pixels
        int birthIndex = -1;            // index into bigBirth for the pop-in, -1 = small line
        float delay, angle, jitter;
        Bitmap whole, topHalf, bottomHalf;
        float pivotX, pivotY;           // bitmap-space point on the cut line (centre of rotation)
        boolean fxDone;

        float cx() { return left + gw * u / 2f; }
        float cy() { return top + 3.5f * u; }
    }

    private final Paint p = new Paint();
    private final Paint bmpP = new Paint();
    private final Random rnd = new Random();
    private final ParticleSystem ps = new ParticleSystem(300);
    private final float density;

    private String small = "";
    private String big = "";
    private String prevBig = "";
    private final long[] bigBirth = new long[64];

    private boolean slicing = false;
    private long sliceStartMs;
    private long lastNs;
    private ArrayList<G> glyphs = new ArrayList<>();
    private Runnable onSliceDone;
    private float sliceTotal;
    private Runnable playKill;          // starts the KILL sound
    private AudioClock killClock;       // playhead of the KILL sound
    private boolean killStarted;
    private long killStartMs;
    private ScenePlan.Clock killTime;   // scene time after the KILL sound started
    private float sliceT;               // monotonic scene time (s)

    DisplayView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
    }

    // -------------------------------------------------------------- public API
    /** Sets both lines. popAll = every character of the big line pops (e.g. after "="). */
    void setTexts(String smallLine, String bigLine, boolean popAll) {
        long now = SystemClock.uptimeMillis();
        if (bigLine.length() > bigBirth.length) bigLine = bigLine.substring(bigLine.length() - bigBirth.length);
        for (int i = 0; i < bigLine.length(); i++) {
            boolean changed = i >= prevBig.length() || prevBig.charAt(i) != bigLine.charAt(i);
            if (popAll || changed) bigBirth[i] = now;
        }
        small = smallLine;
        big = bigLine;
        prevBig = bigLine;
        invalidate();
    }

    boolean isEmpty() { return small.isEmpty() && big.isEmpty(); }

    boolean isSlicing() { return slicing; }

    /**
     * Plays the digit-killing scene; onDone runs once everything has fallen and the screen is empty.
     *
     * @param kill  starts the KILL sound (called once, PRE_ROLL seconds into the scene)
     * @param clock playhead of the KILL sound; the cuts are timed against it
     */
    void startSlice(Runnable kill, AudioClock clock, Runnable onDone) {
        ArrayList<G> list = buildLayout();
        if (list.isEmpty() || getWidth() == 0) {
            small = ""; big = ""; prevBig = "";
            invalidate();
            if (onDone != null) onDone.run();
            return;
        }
        playKill = kill;
        killClock = clock;
        killStarted = false;
        sliceT = 0f;
        releaseBitmaps();
        glyphs = list;
        int n = glyphs.size();
        for (int i = 0; i < n; i++) {
            G g = glyphs.get(i);
            g.delay = n > 1 ? CUT_SPREAD * i / (n - 1) : 0f;
            g.angle = -0.42f + rnd.nextFloat() * 0.22f;
            g.jitter = rnd.nextFloat() * 6.28f;
            buildBitmaps(g);
        }
        sliceTotal = T_CUT + CUT_SPREAD + T_FALL + 0.1f;
        onSliceDone = onDone;
        sliceStartMs = SystemClock.uptimeMillis();
        lastNs = 0;
        slicing = true;
        invalidate();
    }

    // -------------------------------------------------------------- layout
    private void layoutLine(String s, float maxU, float top, int birthMode, ArrayList<G> out) {
        if (s.isEmpty()) return;
        float innerW = getWidth() - 36f * density;
        int units = FontData.textWidth(s);
        float u = Math.max(1f, (float) Math.floor(Math.min(maxU, innerW / units)));
        float x = getWidth() - 18f * density - units * u;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            G g = new G();
            g.ch = ch;
            g.u = u;
            g.gw = FontData.width(ch);
            g.left = x;
            g.top = top;
            g.birthIndex = birthMode == 1 ? i : -1;
            out.add(g);
            x += (g.gw + 1) * u;
        }
    }

    private ArrayList<G> buildLayout() {
        ArrayList<G> out = new ArrayList<>();
        float h = getHeight();
        float uSmall = (float) Math.floor(h * 0.17f / FontData.HEIGHT);
        float uBig = (float) Math.floor(h * 0.42f / FontData.HEIGHT);
        layoutLine(small, uSmall, h * 0.13f, 0, out);
        layoutLine(big, uBig, h * 0.43f, 1, out);
        return out;
    }

    // -------------------------------------------------------------- bitmaps for the kill scene
    private void buildBitmaps(G g) {
        float u = g.u;
        int pad = (int) Math.ceil(u * 2);
        int bw = (int) Math.ceil(g.gw * u + 2 * pad);
        int bh = (int) Math.ceil(FontData.HEIGHT * u + 2 * u + 2 * pad);
        Bitmap whole = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas wc = new Canvas(whole);
        String s = String.valueOf(g.ch);

        // the digit itself, as a little green creature: outlined glyph + eyes + stubby legs
        PixelFont.outlined(wc, p, s, pad, pad, u, ALIEN_FILL, ALIEN_EDGE);
        p.setStyle(Paint.Style.FILL);
        // cyan visor with a highlight, like the character, plus two little horns
        float vx0 = pad + g.gw * u * 0.12f, vx1 = pad + g.gw * u * 0.98f, vy0 = pad + 1.2f * u, vy1 = pad + 3.6f * u;
        p.setColor(0xFF14141E);
        wc.drawRect(vx0 - u * 0.5f, vy0 - u * 0.5f, vx1 + u * 0.5f, vy1 + u * 0.5f, p);
        p.setColor(0xFF59E0FF);
        wc.drawRect(vx0, vy0, vx1, vy1, p);
        p.setColor(0xFFFFFFFF);
        wc.drawRect(vx0 + u * 0.4f, vy0 + u * 0.3f, vx0 + u * 1.6f, vy0 + u * 0.9f, p);
        p.setColor(0xFF4A4A8C);
        wc.drawRect(pad + u * 0.2f, pad - u * 1.2f, pad + u * 1.0f, pad, p);
        wc.drawRect(pad + g.gw * u - u * 1.0f, pad - u * 1.2f, pad + g.gw * u - u * 0.2f, pad, p);
        float legTop = pad + FontData.HEIGHT * u + u * 0.4f;
        for (int i = 0; i < 2; i++) {
            float lx = pad + g.gw * u * (i == 0 ? 0.2f : 0.7f);
            p.setColor(0xFF14141E);
            wc.drawRect(lx - u * 0.4f, legTop - u * 0.4f, lx + u * 1.4f, legTop + 1.8f * u, p);
            p.setColor(0xFF6FA855);
            wc.drawRect(lx, legTop - u * 0.4f, lx + u, legTop + 1.4f * u, p);
        }

        g.pivotX = bw / 2f;
        g.pivotY = pad + 3.5f * u;
        float[] bone = Slicer.boneSpot(whole, g.angle, g.pivotX, g.pivotY, u * 5f);
        float band = u * 1.2f, thick = Math.max(3f, u * 0.9f);
        g.whole = whole;
        g.topHalf = Slicer.half(whole, g.angle, g.pivotX, g.pivotY, true, band, bone, thick);
        g.bottomHalf = Slicer.half(whole, g.angle, g.pivotX, g.pivotY, false, band, bone, thick);
    }

    private void releaseBitmaps() {
        for (G g : glyphs) {
            if (g.whole != null) g.whole.recycle();
            if (g.topHalf != null) g.topHalf.recycle();
            if (g.bottomHalf != null) g.bottomHalf.recycle();
            g.whole = g.topHalf = g.bottomHalf = null;
        }
        glyphs = new ArrayList<>();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releaseBitmaps();
    }

    // -------------------------------------------------------------- drawing
    private static int alpha(int color, float a) {
        int al = Math.max(0, Math.min(255, Math.round(a * 255f)));
        return (al << 24) | (color & 0x00FFFFFF);
    }

    private static float easeOutBack(float t) {
        float c1 = 2.2f, c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private static float smooth(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3f - 2f * x);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        drawScreen(c, w, h);

        long now = SystemClock.uptimeMillis();
        boolean needFrame = false;

        if (slicing) {
            long ns = System.nanoTime();
            float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (ns - lastNs) / 1e9f);
            lastNs = ns;
            float t = sliceClock(now, dt);
            drawSlice(c, t);
            ps.update(dt);
            ps.draw(c, p);
            if (t >= sliceTotal) {
                slicing = false;
                small = ""; big = ""; prevBig = "";
                releaseBitmaps();
                Runnable r = onSliceDone;
                onSliceDone = null;
                invalidate();
                if (r != null) r.run();
            } else {
                needFrame = true;
            }
        } else {
            for (G g : buildLayout()) {
                float sc = 1f, a = 1f;
                if (g.birthIndex >= 0) {
                    float age = (now - bigBirth[g.birthIndex]) / 1000f;
                    if (age < 0.25f) {
                        float k = age / 0.25f;
                        sc = easeOutBack(k);
                        a = Math.min(1f, k * 3f);
                        needFrame = true;
                    }
                }
                boolean isBig = g.birthIndex >= 0;
                c.save();
                c.scale(sc, sc, g.cx(), g.cy());
                PixelFont.shadowed(c, p, String.valueOf(g.ch), g.left, g.top, g.u,
                        alpha(isBig ? NEON : DIM, a), alpha(isBig ? NEON_SHADOW : DIM_SHADOW, a));
                c.restore();
            }
            ps.update(0.016f);
            if (ps.hasActive()) { ps.draw(c, p); needFrame = true; }
        }
        // the status LED blinks, so keep a slow heartbeat going
        if (needFrame) postInvalidateOnAnimation(); else postInvalidateDelayed(600);
    }

    /**
     * Scene time. Before the KILL sound starts it is plain wall time; afterwards it follows the audio
     * playhead (and falls back to wall time if no audio is playing), so the cuts land on the hit.
     */
    private float sliceClock(long now, float dt) {
        float wall = (now - sliceStartMs) / 1000f;
        if (!killStarted) {
            if (wall < PRE_ROLL) {
                sliceT = wall;
                return sliceT;
            }
            killStarted = true;
            killStartMs = now;
            killTime = new ScenePlan.Clock(ScenePlan.KILL_LEN);
            sliceT = PRE_ROLL;
            if (playKill != null) playKill.run();
        }
        float since = (now - killStartMs) / 1000f;
        float ph = killClock != null ? killClock.playheadSec() : -1f;
        sliceT = PRE_ROLL + killTime.update(since, ph, dt);
        return sliceT;
    }

    /** Stepped-corner LCD with scan lines, a pixel frame and a blinking LED. */
    private void drawScreen(Canvas c, float w, float h) {
        float u = Math.max(2f, Math.round(density * 2f));
        p.setStyle(Paint.Style.FILL);
        steppedRect(c, 0, 0, w, h, u, 0xFF05050F);
        float glow = 0.5f + 0.5f * (float) Math.sin(SystemClock.uptimeMillis() / 700.0);
        steppedRect(c, u, u, w - u, h - u, u, 0xFF2BE86A);
        steppedRect(c, 2 * u, 2 * u, w - 2 * u, h - 2 * u, u, 0xFF0B3A2A);          // inner neon rim
        steppedRect(c, 3 * u, 3 * u, w - 3 * u, h - 3 * u, u, 0xFF03140F);
        p.setColor(0x6659FF8A);                                                      // pulsing rim light
        p.setAlpha((int) (60 + 120 * glow));
        c.drawRect(4 * u, 2 * u, w - 4 * u, 3 * u, p);
        c.drawRect(4 * u, h - 3 * u, w - 4 * u, h - 2 * u, p);
        p.setAlpha(255);
        // twinkling four-point sparkles inside the glass
        float[][] sp = {{0.07f, 0.28f, 0f}, {0.30f, 0.72f, 1.7f}, {0.46f, 0.30f, 3.1f}, {0.18f, 0.80f, 4.4f}};
        for (float[] q : sp) {
            float tw = 0.5f + 0.5f * (float) Math.sin(SystemClock.uptimeMillis() / 520.0 + q[2]);
            float su = Math.max(2f, u * (0.8f + tw)), x = Math.round(w * q[0]), y = Math.round(h * q[1]);
            p.setColor(0xFF59FF8A);
            p.setAlpha((int) (80 + 175 * tw));
            c.drawRect(x - su, y, x + 2 * su, y + su, p);
            c.drawRect(x, y - su, x + su, y + 2 * su, p);
            p.setAlpha(255);
        }
        // scan lines
        p.setColor(0x1428FFC8);
        for (float y = 3 * u; y < h - 3 * u; y += 2 * u) c.drawRect(2 * u, y, w - 2 * u, y + u * 0.5f, p);
        // glass sheen
        p.setColor(0x14FFFFFF);
        c.drawRect(3 * u, 3 * u, w - 3 * u, 3 * u + h * 0.07f, p);
        // LED
        boolean on = (SystemClock.uptimeMillis() / 600) % 2 == 0;
        p.setColor(on ? 0xFF7BFF6B : 0xFF1E5A2A);
        c.drawRect(5 * u, 5 * u, 7 * u, 7 * u, p);
    }

    private void steppedRect(Canvas c, float l, float t, float r, float b, float u, int color) {
        p.setColor(color);
        c.drawRect(l + 2 * u, t, r - 2 * u, b, p);
        c.drawRect(l + u, t + u, r - u, b - u, p);
        c.drawRect(l, t + 2 * u, r, b - 2 * u, p);
    }

    private void drawSlice(Canvas c, float t) {
        for (int i = 0; i < glyphs.size(); i++) {
            G g = glyphs.get(i);
            if (g.whole == null) continue;
            float cutT = T_CUT + g.delay;
            float u = g.u;
            float size = 9f * u;
            float cx = g.cx(), cy = g.cy();

            if (t < cutT) {
                // ---- alive: neon digit -> creature -> panic ----
                float a = smooth(t / T_ALIEN);
                float pop = 1f + 0.2f * (float) Math.sin(Math.PI * Math.min(1f, t / T_ALIEN));
                float ox = 0f, oy = 0f;
                if (t > T_PANIC) {
                    float k = Math.min(1f, (t - T_PANIC) / 0.2f);
                    ox = (float) Math.sin(t * 70f + g.jitter) * size * 0.04f * k;
                    oy = -Math.abs((float) Math.sin(t * 13f + g.jitter)) * size * 0.14f * k;
                }
                if (a < 1f) {
                    c.save();
                    c.scale(pop, pop, cx, cy);
                    PixelFont.shadowed(c, p, String.valueOf(g.ch), g.left, g.top, u,
                            alpha(NEON, 1f - a), alpha(NEON_SHADOW, 1f - a));
                    c.restore();
                }
                c.save();
                c.translate(ox, oy);
                c.scale(pop, pop, cx, cy);
                bmpP.setAlpha((int) (a * 255));
                c.drawBitmap(g.whole, cx - g.pivotX, cy - g.pivotY, bmpP);
                bmpP.setAlpha(255);
                c.restore();

                if (t > T_PANIC && ((int) (t * 12f + g.jitter) % 2 == 0)) {   // blinking "!" alarm
                    float au = Math.max(2f, Math.round(u * 0.6f));
                    PixelFont.outlined(c, p, "!", cx - au / 2f + ox, g.top - 8.5f * au + oy, au,
                            0xFFFFE066, 0xFF5A0A0A);
                }
                if (t > cutT - 0.08f) drawSlash(c, g, (t - (cutT - 0.08f)) / 0.08f, 1f);
            } else {
                float k = t - cutT;
                if (!g.fxDone) {
                    g.fxDone = true;
                    ps.sparks(cx, cy, 14, 0xFFFFE066, size * 5f);
                    ps.sparks(cx, cy, 6, 0xFFFF4D4D, size * 3.5f);
                    ps.smoke(cx, cy, 4, size * 0.35f);
                }
                if (k < 0.14f) drawSlash(c, g, 1f, 1f - k / 0.14f);

                float fade = 1f - smooth((k - 0.35f) / 0.8f);
                if (fade <= 0f) continue;
                float grav = size * 1.7f;
                float open = smooth(k / 0.12f);
                float fall = 0.5f * grav * k * k;

                // top piece: kicked up/left, spins counter-clockwise
                drawHalf(c, g.topHalf, g, -size * 0.4f * smooth(k / 0.6f),
                        -size * 0.14f * open + fall, -75f * k, fade);
                // bottom piece: drops right, spins clockwise
                drawHalf(c, g.bottomHalf, g, size * 0.25f * smooth(k / 0.6f),
                        size * 0.06f * open + fall * 1.15f, 60f * k, fade);

                if (k < 0.5f && rnd.nextFloat() < 0.3f) {
                    ps.smoke(cx + (rnd.nextFloat() - 0.5f) * size * 0.5f, cy + fall, 1, size * 0.22f);
                }
            }
        }
    }

    private void drawHalf(Canvas c, Bitmap b, G g, float dx, float dy, float rot, float alpha) {
        c.save();
        c.translate(g.cx() + dx, g.cy() + dy);
        c.rotate(rot);
        bmpP.setAlpha((int) (alpha * 255));
        c.drawBitmap(b, -g.pivotX, -g.pivotY, bmpP);
        bmpP.setAlpha(255);
        c.restore();
    }

    /** Bright cutting line through the glyph. reveal 0..1 = how far the blade has travelled. */
    private void drawSlash(Canvas c, G g, float reveal, float alpha) {
        float dx = (float) Math.cos(g.angle), dy = (float) Math.sin(g.angle);
        float half = g.u * 7f;
        float cx = g.cx(), cy = g.cy();
        float x0 = cx - dx * half, y0 = cy - dy * half;
        float r = Math.max(0f, Math.min(1f, reveal));
        float x1 = x0 + dx * half * 2f * r, y1 = y0 + dy * half * 2f * r;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.SQUARE);
        p.setColor(0xFF7DF9FF);
        p.setStrokeWidth(g.u * 1.8f);
        p.setAlpha((int) (70 * alpha));
        c.drawLine(x0, y0, x1, y1, p);
        p.setColor(0xFFFFFFFF);
        p.setStrokeWidth(Math.max(2f, g.u * 0.6f));
        p.setAlpha((int) (255 * alpha));
        c.drawLine(x0, y0, x1, y1, p);
        p.setAlpha(255);
        p.setStyle(Paint.Style.FILL);
    }
}
