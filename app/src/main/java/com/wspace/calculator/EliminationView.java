package com.wspace.calculator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Full-screen cinematic "ELIMINATED!" scene (about 2.8 s), all in pixel art:
 *  0.0-0.9  the little green character slides into frame, a red alarm frame pulses, black bars close in
 *  0.9      laser slash + white flash: the character is cut in two (red inside, small white bone)
 *  0.9-2.8  the halves tumble away with sparks and smoke, "ELIMINATED!" slams onto the screen
 */
final class EliminationView extends View {

    static final float DURATION = 2.8f;

    private static final float T_HIT = 0.9f;
    private static final float T_TEXT = 1.0f;
    private static final float CUT_ANGLE = -0.38f;      // radians

    private final Paint p = new Paint();
    private final Paint bmpP = new Paint();
    private final ParticleSystem ps = new ParticleSystem(360);
    private final Random rnd = new Random(3);

    private final float[] sx = new float[60], sy = new float[60], sv = new float[60];

    private Bitmap whole, topHalf, bottomHalf;
    private float pivotX, pivotY;
    private int su;                                      // size of one sprite pixel in px

    private boolean running = false;
    private boolean hitDone = false;
    private long startMs;
    private long lastNs;
    private Runnable onDone;

    EliminationView(Context ctx) {
        super(ctx);
        setClickable(true); // swallow touches while the scene plays
        setVisibility(GONE);
    }

    void start(Runnable done) {
        onDone = done;
        running = true;
        hitDone = false;
        buildSprites();
        startMs = SystemClock.uptimeMillis();
        lastNs = 0;
        setAlpha(1f);
        setVisibility(VISIBLE);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        for (int i = 0; i < sx.length; i++) {
            sx[i] = rnd.nextFloat() * w;
            sy[i] = rnd.nextFloat() * h;
            sv[i] = w * (0.2f + rnd.nextFloat() * 1.2f);
        }
    }

    private void buildSprites() {
        releaseBitmaps();
        int h = getHeight() > 0 ? getHeight() : 1600;
        su = Math.max(3, (int) Math.floor(h * 0.30f / SpriteData.H));
        whole = Sprite.render(p, su);
        pivotX = whole.getWidth() / 2f;
        pivotY = whole.getHeight() * 0.52f;
        float[] bone = Slicer.boneSpot(whole, CUT_ANGLE, pivotX, pivotY, su * 9f);
        float band = su * 1.6f, thick = Math.max(4f, su * 1.2f);
        topHalf = Slicer.half(whole, CUT_ANGLE, pivotX, pivotY, true, band, bone, thick);
        bottomHalf = Slicer.half(whole, CUT_ANGLE, pivotX, pivotY, false, band, bone, thick);
    }

    private void releaseBitmaps() {
        if (whole != null) whole.recycle();
        if (topHalf != null) topHalf.recycle();
        if (bottomHalf != null) bottomHalf.recycle();
        whole = topHalf = bottomHalf = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releaseBitmaps();
    }

    private static float smooth(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3f - 2f * x);
    }

    private static int alpha(int color, float a) {
        int al = Math.max(0, Math.min(255, Math.round(a * 255f)));
        return (al << 24) | (color & 0x00FFFFFF);
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w == 0 || whole == null) return;

        float tReal = (SystemClock.uptimeMillis() - startMs) / 1000f;
        float t = Math.min(tReal, DURATION);
        long ns = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (ns - lastNs) / 1e9f);
        lastNs = ns;

        float ax = w * 0.5f, ay = h * 0.36f;
        float spriteH = SpriteData.H * su;

        float shakeK = Math.max(0f, 1f - (t - T_HIT) / 0.55f);
        float shake = t >= T_HIT ? w * 0.022f * shakeK : 0f;
        c.save();
        c.translate(Math.round((float) Math.sin(t * 95f) * shake), Math.round((float) Math.cos(t * 83f) * shake));

        // ---- backdrop: dark sky with speed lines, pulsing red alarm frame ----
        c.drawColor(0xFF05030A);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        for (int i = 0; i < sx.length; i++) {
            float x = ((sx[i] - sv[i] * t) % w + w) % w;
            p.setAlpha(120);
            c.drawRect(x, sy[i], x + sv[i] * 0.06f, sy[i] + 3f, p);
        }
        p.setAlpha(255);

        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 14f);
        float vigA = t < T_HIT ? (0.25f + 0.5f * smooth(t / T_HIT)) * (0.6f + 0.4f * pulse) : (0.8f + 0.2f * pulse);
        float fu = Math.max(6f, Math.round(w * 0.018f));
        for (int i = 0; i < 4; i++) {
            p.setColor(alpha(0xFFFF1A30, vigA * (0.85f - i * 0.2f)));
            float o = i * fu;
            c.drawRect(o, o, w - o, o + fu, p);
            c.drawRect(o, h - o - fu, w - o, h - o, p);
            c.drawRect(o, o + fu, o + fu, h - o - fu, p);
            c.drawRect(w - o - fu, o + fu, w - o, h - o - fu, p);
        }

        // ---- the character ----
        if (t < T_HIT) {
            float k = smooth(t / T_HIT);
            float x = -w * 0.25f + (ax + w * 0.25f) * k;
            float y = ay + (float) Math.sin(t * 3.4f) * h * 0.01f;
            int step = ((int) (t / 0.1f)) % 2;
            Sprite.draw(c, p, Math.round(x - SpriteData.W * su / 2f), Math.round(y - spriteH / 2f), su, false, step);
            if (t > 0.45f && ((int) (t * 12f) % 2 == 0)) {          // blinking alarm mark
                float au = Math.max(4f, su * 1.4f);
                PixelFont.outlined(c, p, "!", x + SpriteData.W * su * 0.5f, y - spriteH * 0.75f, au,
                        0xFFFFE066, 0xFF5A0A0A);
            }
        } else {
            if (!hitDone) {
                hitDone = true;
                ps.sparks(ax, ay, 46, 0xFFFFE066, w * 1.1f);
                ps.sparks(ax, ay, 30, 0xFFFF3B3B, w * 0.8f);
                ps.sparks(ax, ay, 18, 0xFFFFFFFF, w * 1.3f);
                ps.smoke(ax, ay, 16, spriteH * 0.25f);
            }
            float kt = t - T_HIT;
            float fall = 0.5f * h * 1.1f * kt * kt;
            float fade = 1f - smooth((kt - 1.1f) / 0.7f);
            if (fade > 0f) {
                drawHalf(c, topHalf, ax - w * 0.2f * smooth(kt / 0.8f),
                        ay - h * 0.06f * smooth(kt / 0.25f) + fall, -70f * kt, fade);
                drawHalf(c, bottomHalf, ax + w * 0.16f * smooth(kt / 0.8f),
                        ay + h * 0.02f * smooth(kt / 0.25f) + fall * 1.2f, 55f * kt, fade);
            }
            if (kt < 0.6f) {                                         // square shockwave
                float rk = kt / 0.6f;
                float rad = spriteH * (0.4f + 1.9f * rk);
                float th = Math.max(4f, su * 1.2f * (1f - rk));
                p.setColor(alpha(0xFFFFFFFF, 1f - rk));
                c.drawRect(ax - rad, ay - rad, ax + rad, ay - rad + th, p);
                c.drawRect(ax - rad, ay + rad - th, ax + rad, ay + rad, p);
                c.drawRect(ax - rad, ay - rad + th, ax - rad + th, ay + rad - th, p);
                c.drawRect(ax + rad - th, ay - rad + th, ax + rad, ay + rad - th, p);
            }
        }

        // laser slash just before / during the hit
        if (t > T_HIT - 0.12f && t < T_HIT + 0.3f) {
            float reveal = Math.min(1f, (t - (T_HIT - 0.12f)) / 0.12f);
            float a = t < T_HIT ? 1f : 1f - (t - T_HIT) / 0.3f;
            float dx = (float) Math.cos(CUT_ANGLE), dy = (float) Math.sin(CUT_ANGLE);
            float len = w * 0.9f;
            float x0 = ax - dx * len / 2f, y0 = ay - dy * len / 2f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.SQUARE);
            p.setColor(0xFFFF3040);
            p.setStrokeWidth(su * 3f);
            p.setAlpha((int) (90 * a));
            c.drawLine(x0, y0, x0 + dx * len * reveal, y0 + dy * len * reveal, p);
            p.setColor(0xFFFFFFFF);
            p.setStrokeWidth(su * 0.9f);
            p.setAlpha((int) (255 * a));
            c.drawLine(x0, y0, x0 + dx * len * reveal, y0 + dy * len * reveal, p);
            p.setAlpha(255);
            p.setStyle(Paint.Style.FILL);
        }

        ps.update(dt);
        ps.draw(c, p);

        // ---- ELIMINATED! ----
        if (t >= T_TEXT) drawTitle(c, w, h, t - T_TEXT);

        // cinematic bars
        float bar = h * 0.08f * smooth(t / 0.3f);
        p.setColor(0xFF000000);
        c.drawRect(-w * 0.1f, -h * 0.1f, w * 1.1f, bar, p);
        c.drawRect(-w * 0.1f, h - bar, w * 1.1f, h * 1.1f, p);
        c.restore();

        // white impact flash (not shaken)
        if (t >= T_HIT && t < T_HIT + 0.18f) {
            p.setColor(alpha(0xFFFFFFFF, 0.9f * (1f - (t - T_HIT) / 0.18f)));
            c.drawRect(0, 0, w, h, p);
            p.setAlpha(255);
        }

        if (tReal >= DURATION) {
            running = false;
            Runnable r = onDone;
            onDone = null;
            if (r != null) r.run();
        } else if (running) {
            postInvalidateOnAnimation();
        }
    }

    private void drawHalf(Canvas c, Bitmap b, float cx, float cy, float rot, float alpha) {
        c.save();
        c.translate(cx, cy);
        c.rotate(rot);
        bmpP.setAlpha((int) (alpha * 255));
        c.drawBitmap(b, -pivotX, -pivotY, bmpP);
        bmpP.setAlpha(255);
        c.restore();
    }

    private void drawTitle(Canvas c, float w, float h, float ts) {
        String text = "ELIMINATED!";
        float u = Math.max(4f, (float) Math.floor(w * 0.92f / FontData.textWidth(text)));
        float tw = FontData.textWidth(text) * u;
        float th = FontData.HEIGHT * u;

        float k = Math.min(1f, ts / 0.25f);
        float scale = 1f + 2.4f * (1f - k) * (1f - k);
        float a = Math.min(1f, ts / 0.08f);
        float cx = w / 2f, cy = h * 0.66f;
        float x = cx - tw / 2f, y = cy - th / 2f;

        c.save();
        c.scale(scale, scale, cx, cy);
        // hard black shadow, white outline, red fill, yellow highlight on the top rows
        PixelFont.text(c, p, text, x + u * 1.2f, y + u * 1.6f, u, alpha(0xFF000000, a));
        PixelFont.outlined(c, p, text, x, y, u, alpha(0xFFE0152A, a), alpha(0xFFFFFFFF, a));
        c.save();
        c.clipRect(x - u, y - u, x + tw + u, y + 2.2f * u);
        PixelFont.text(c, p, text, x, y, u, alpha(0xFFFFE27A, a));
        c.restore();
        c.restore();

        if (ts > 0.35f) {
            float sa = smooth((ts - 0.35f) / 0.3f);
            String sub = "TOO MANY OPERATIONS";
            float su2 = Math.max(2f, (float) Math.floor(w * 0.7f / FontData.textWidth(sub)));
            float sw = FontData.textWidth(sub) * su2;
            PixelFont.shadowed(c, p, sub, Math.round(cx - sw / 2f), Math.round(cy + th / 2f + u * 2.5f), su2,
                    alpha(0xFFFFFFFF, sa), alpha(0xFF000000, sa));
        }
    }
}
