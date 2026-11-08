package com.wspace.calculator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Full-screen cinematic "ELIMINATED!" scene (about 2.8 s):
 *  0.0-0.9  a green alien drifts into frame in side view while red lights pulse and the bars close in
 *  0.9      laser slash + white flash, the alien is cut in two (red inside, small white bone)
 *  0.9-2.8  halves tumble away with sparks and smoke, "ELIMINATED!" slams onto the screen
 */
final class EliminationView extends View {

    static final float DURATION = 2.8f;

    private static final float T_HIT = 0.9f;
    private static final float T_TEXT = 1.0f;
    private static final float CUT_ANGLE = -0.38f;      // radians, in the alien's own space

    private static final GlyphStyle RED = new GlyphStyle(0xFFFFE27A, 0xFFE0152A, 0xFF2A0006, 0xFFFF2030);

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textP = new Paint();
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Path poly = new Path();
    private final AlienPainter alien = new AlienPainter();
    private final ParticleSystem ps = new ParticleSystem(360);
    private final Random rnd = new Random(3);

    private final float[] sx = new float[70], sy = new float[70], sv = new float[70];
    private Shader vignette;

    private boolean running = false;
    private boolean hitDone = false;
    private long startMs;
    private long lastNs;
    private Runnable onDone;

    EliminationView(Context ctx) {
        super(ctx);
        GlyphStyle.setup(textP);
        setClickable(true); // swallow touches while the scene plays
        setVisibility(GONE);
    }

    void start(Runnable done) {
        onDone = done;
        running = true;
        hitDone = false;
        startMs = SystemClock.uptimeMillis();
        lastNs = 0;
        setAlpha(1f);
        setVisibility(VISIBLE);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        vignette = new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f,
                new int[]{0x00000000, 0x00000000, 0xFFFF1020}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        for (int i = 0; i < sx.length; i++) {
            sx[i] = rnd.nextFloat() * w;
            sy[i] = rnd.nextFloat() * h;
            sv[i] = w * (0.2f + rnd.nextFloat() * 1.2f);
        }
    }

    private static float smooth(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3f - 2f * x);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w == 0 || vignette == null) return;

        float tReal = (SystemClock.uptimeMillis() - startMs) / 1000f;
        float t = Math.min(tReal, DURATION);
        long ns = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (ns - lastNs) / 1e9f);
        lastNs = ns;

        float ax = w * 0.5f, ay = h * 0.4f;
        float halfW = w * 0.2f;

        // screen shake after the hit
        float shakeK = Math.max(0f, 1f - (t - T_HIT) / 0.55f);
        float shake = t >= T_HIT ? w * 0.022f * shakeK : 0f;

        c.save();
        c.translate((float) Math.sin(t * 95f) * shake, (float) Math.cos(t * 83f) * shake);

        // ---- backdrop ----
        c.drawColor(0xFF020106);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFFFFFFFF);
        for (int i = 0; i < sx.length; i++) {
            float x = ((sx[i] - sv[i] * t) % w + w) % w;
            p.setStrokeWidth(1.5f + (sv[i] / w));
            p.setAlpha(150);
            c.drawLine(x, sy[i], x + sv[i] * 0.06f, sy[i], p);
        }
        p.setAlpha(255);
        p.setStyle(Paint.Style.FILL);

        // pulsing red emergency light
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 14f);
        float vigA = t < T_HIT ? (0.25f + 0.45f * smooth(t / T_HIT)) * (0.6f + 0.4f * pulse)
                : (0.75f + 0.25f * pulse);
        p.setShader(vignette);
        p.setAlpha((int) (255 * Math.min(1f, vigA)));
        c.drawRect(-w * 0.1f, -h * 0.1f, w * 1.1f, h * 1.1f, p);
        p.setShader(null);
        p.setAlpha(255);

        // rotating light rays once the text lands
        if (t > T_TEXT) {
            c.save();
            c.translate(w / 2f, h * 0.62f);
            c.rotate(t * 25f);
            p.setColor(0xFFFF3040);
            p.setAlpha(34);
            float R = Math.max(w, h);
            for (int i = 0; i < 12; i++) {
                path.reset();
                path.moveTo(0, 0);
                double a0 = i * Math.PI / 6.0, a1 = a0 + Math.PI / 18.0;
                path.lineTo((float) Math.cos(a0) * R, (float) Math.sin(a0) * R);
                path.lineTo((float) Math.cos(a1) * R, (float) Math.sin(a1) * R);
                path.close();
                c.drawPath(path, p);
            }
            c.restore();
            p.setAlpha(255);
        }

        // ---- the alien ----
        if (t < T_HIT) {
            float k = smooth(t / T_HIT);
            float x = -w * 0.2f + (ax + w * 0.2f) * k;
            float y = ay + (float) Math.sin(t * 3.4f) * h * 0.012f;
            drawGlow(c, x, y, halfW);
            alien.draw(c, x, y, halfW, 7f + (float) Math.sin(t * 2.2f) * 4f, t, 0.9f);
        } else {
            if (!hitDone) {
                hitDone = true;
                ps.sparks(ax, ay, 46, 0xFFFFE066, w * 1.1f);
                ps.sparks(ax, ay, 30, 0xFFFF3B3B, w * 0.8f);
                ps.sparks(ax, ay, 18, 0xFFFFFFFF, w * 1.3f);
                ps.smoke(ax, ay, 16, halfW * 0.5f);
            }
            float kt = t - T_HIT;
            float g = h * 1.1f;
            float fall = 0.5f * g * kt * kt;
            float fade = 1f - smooth((kt - 1.1f) / 0.7f);
            float tilt0 = 7f;
            if (fade > 0f) {
                drawAlienHalf(c, true, ax - w * 0.2f * smooth(kt / 0.8f),
                        ay - h * 0.06f * smooth(kt / 0.25f) + fall, halfW,
                        tilt0 - 70f * kt, t, fade);
                drawAlienHalf(c, false, ax + w * 0.16f * smooth(kt / 0.8f),
                        ay + h * 0.02f * smooth(kt / 0.25f) + fall * 1.2f, halfW,
                        tilt0 + 55f * kt, t, fade);
            }
            // shockwave ring
            if (kt < 0.6f) {
                float rk = kt / 0.6f;
                p.setStyle(Paint.Style.STROKE);
                p.setColor(0xFFFFFFFF);
                p.setStrokeWidth(halfW * 0.12f * (1f - rk) + 2f);
                p.setAlpha((int) (200 * (1f - rk)));
                c.drawCircle(ax, ay, halfW * (0.5f + 3.2f * rk), p);
                p.setAlpha(255);
                p.setStyle(Paint.Style.FILL);
            }
        }

        // laser slash just before / during the hit
        if (t > T_HIT - 0.12f && t < T_HIT + 0.3f) {
            float reveal = Math.min(1f, (t - (T_HIT - 0.12f)) / 0.12f);
            float alpha = t < T_HIT ? 1f : 1f - (t - T_HIT) / 0.3f;
            float dx = (float) Math.cos(CUT_ANGLE), dy = (float) Math.sin(CUT_ANGLE);
            float len = w * 0.9f;
            float x0 = ax - dx * len / 2f, y0 = ay + halfW * 0.05f - dy * len / 2f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFFFF3040);
            p.setStrokeWidth(halfW * 0.28f);
            p.setAlpha((int) (90 * alpha));
            c.drawLine(x0, y0, x0 + dx * len * reveal, y0 + dy * len * reveal, p);
            p.setColor(0xFFFFFFFF);
            p.setStrokeWidth(halfW * 0.07f);
            p.setAlpha((int) (255 * alpha));
            c.drawLine(x0, y0, x0 + dx * len * reveal, y0 + dy * len * reveal, p);
            p.setAlpha(255);
            p.setStyle(Paint.Style.FILL);
        }

        ps.update(dt);
        ps.draw(c, p);

        // ---- ELIMINATED! ----
        if (t >= T_TEXT) drawTitle(c, w, h, t - T_TEXT);

        // cinematic bars
        float bar = h * 0.09f * smooth(t / 0.3f);
        p.setColor(0xFF000000);
        c.drawRect(-w * 0.1f, -h * 0.1f, w * 1.1f, bar, p);
        c.drawRect(-w * 0.1f, h - bar, w * 1.1f, h * 1.1f, p);

        c.restore();

        // white impact flash (not shaken)
        if (t >= T_HIT && t < T_HIT + 0.18f) {
            p.setColor(0xFFFFFFFF);
            p.setAlpha((int) (230 * (1f - (t - T_HIT) / 0.18f)));
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

    private void drawGlow(Canvas c, float x, float y, float halfW) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF39FF88);
        p.setAlpha(24);
        c.drawCircle(x, y, halfW * 1.9f, p);
        p.setAlpha(255);
    }

    private void drawAlienHalf(Canvas c, boolean upper, float cx, float cy, float halfW,
                               float rotDeg, float time, float alpha) {
        float dx = (float) Math.cos(CUT_ANGLE), dy = (float) Math.sin(CUT_ANGLE);
        float nx = dy, ny = -dx;
        float s = upper ? 1f : -1f;
        float R = 6f, py = 0.05f;

        c.save();
        c.translate(cx, cy);
        c.rotate(rotDeg);
        c.scale(halfW, halfW);

        poly.reset();
        poly.moveTo(-dx * R, py - dy * R);
        poly.lineTo(dx * R, py + dy * R);
        poly.lineTo(dx * R + s * nx * R, py + dy * R + s * ny * R);
        poly.lineTo(-dx * R + s * nx * R, py - dy * R + s * ny * R);
        poly.close();
        c.clipPath(poly);

        if (alpha < 1f) c.saveLayerAlpha(-4f, -4f, 4f, 4f, (int) (alpha * 255));
        alien.drawUnit(c, time, 0.9f);

        // red flesh across the cut, limited to the body outline
        c.save();
        c.clipPath(alien.bodyPath);
        float band = 0.17f;
        p.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(-dx * R, py - dy * R);
        path.lineTo(dx * R, py + dy * R);
        path.lineTo(dx * R + s * nx * band, py + dy * R + s * ny * band);
        path.lineTo(-dx * R + s * nx * band, py - dy * R + s * ny * band);
        path.close();
        p.setColor(0xFFE5202E);
        c.drawPath(path, p);
        path.reset();
        path.moveTo(-dx * R, py - dy * R);
        path.lineTo(dx * R, py + dy * R);
        path.lineTo(dx * R + s * nx * band * 0.3f, py + dy * R + s * ny * band * 0.3f);
        path.lineTo(-dx * R + s * nx * band * 0.3f, py - dy * R + s * ny * band * 0.3f);
        path.close();
        p.setColor(0xFF9E0F1B);
        c.drawPath(path, p);
        c.restore();

        // small white bone in the middle of the cut
        c.save();
        c.translate(0f, py);
        c.rotate((float) Math.toDegrees(CUT_ANGLE));
        p.setColor(0xFFFFFFF0);
        rect.set(-0.34f, -0.05f, 0.34f, 0.05f);
        c.drawRoundRect(rect, 0.05f, 0.05f, p);
        for (int e = -1; e <= 1; e += 2) {
            for (int k = -1; k <= 1; k += 2) {
                c.drawCircle(e * 0.34f, k * 0.065f, 0.085f, p);
            }
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.015f);
        p.setColor(0xFFB9B9A0);
        c.drawRoundRect(rect, 0.05f, 0.05f, p);
        p.setStyle(Paint.Style.FILL);
        c.restore();

        if (alpha < 1f) c.restore();
        c.restore();
    }

    private void drawTitle(Canvas c, float w, float h, float ts) {
        String text = "ELIMINATED!";
        textP.setTextSize(100f);
        float measured = textP.measureText(text);
        float size = 100f * (w * 0.92f) / measured;

        float k = Math.min(1f, ts / 0.25f);
        float scale = 1f + 2.4f * (1f - k) * (1f - k);
        scale += 0.04f * (float) Math.sin(ts * 12f) * (float) Math.exp(-ts * 3f);
        float alpha = Math.min(1f, ts / 0.08f);

        float cx = w / 2f, cy = h * 0.66f;
        c.save();
        c.translate(cx, cy);
        c.rotate(-6f);
        c.scale(scale, scale);
        c.saveLayerAlpha(-w, -h * 0.3f, w, h * 0.3f, (int) (alpha * 255));
        // chunky white edge behind for readability
        textP.setTextSize(size);
        textP.setStyle(Paint.Style.STROKE);
        textP.setShader(null);
        textP.setColor(0xFFFFFFFF);
        textP.setStrokeWidth(size * 0.2f);
        c.drawText(text, 0, size * 0.35f, textP);
        textP.setStyle(Paint.Style.FILL);
        RED.draw(c, textP, text, 0, size * 0.35f, size, 1.2f);
        c.restore();

        // subtitle
        if (ts > 0.35f) {
            float sa = smooth((ts - 0.35f) / 0.3f);
            float ss = size * 0.2f;
            c.saveLayerAlpha(-w, -h, w * 2, h * 2, (int) (sa * 255));
            GlyphStyle.WHITE.draw(c, textP, "TOO MANY OPERATIONS", 0, ss * 1.2f + h * 0.12f, ss, 0.8f);
            c.restore();
        }
        c.restore();
    }
}
