package com.wspace.calculator;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Random;

/** Fixed-capacity particle pool (sparks, smoke puffs, twinkling stars). No allocation per frame. */
final class ParticleSystem {
    static final int SPARK = 0, SMOKE = 1, STAR = 2;

    private final int cap;
    private final float[] x, y, vx, vy, life, maxLife, size;
    private final int[] color;
    private final int[] type;
    private int next = 0;
    private final Random rnd = new Random();

    ParticleSystem(int capacity) {
        cap = capacity;
        x = new float[cap]; y = new float[cap];
        vx = new float[cap]; vy = new float[cap];
        life = new float[cap]; maxLife = new float[cap]; size = new float[cap];
        color = new int[cap]; type = new int[cap];
    }

    void spawn(int t, float px, float py, float pvx, float pvy, float l, float s, int c) {
        int i = next;
        next = (next + 1) % cap;
        type[i] = t; x[i] = px; y[i] = py; vx[i] = pvx; vy[i] = pvy;
        life[i] = l; maxLife[i] = l; size[i] = s; color[i] = c;
    }

    void sparks(float px, float py, int n, int c, float speed) {
        for (int k = 0; k < n; k++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            float sp = speed * (0.35f + rnd.nextFloat() * 0.65f);
            spawn(SPARK, px, py, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp,
                    0.35f + rnd.nextFloat() * 0.5f, 2f + rnd.nextFloat() * 2.5f, c);
        }
    }

    void smoke(float px, float py, int n, float baseSize) {
        for (int k = 0; k < n; k++) {
            float ang = (float) (rnd.nextDouble() * Math.PI * 2);
            float sp = 10f + rnd.nextFloat() * 40f;
            spawn(SMOKE, px + (rnd.nextFloat() - 0.5f) * baseSize, py + (rnd.nextFloat() - 0.5f) * baseSize,
                    (float) Math.cos(ang) * sp, (float) Math.sin(ang) * sp - 25f,
                    0.7f + rnd.nextFloat() * 0.7f, baseSize * (0.5f + rnd.nextFloat() * 0.7f),
                    0xFF8A93A8);
        }
    }

    void update(float dt) {
        for (int i = 0; i < cap; i++) {
            if (life[i] <= 0f) continue;
            life[i] -= dt;
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            if (type[i] == SPARK) {
                vy[i] += 380f * dt;
                vx[i] *= (1f - 1.2f * dt);
            } else if (type[i] == SMOKE) {
                vx[i] *= (1f - 1.5f * dt);
                vy[i] *= (1f - 1.0f * dt);
                size[i] += 22f * dt;
            }
        }
    }

    boolean hasActive() {
        for (int i = 0; i < cap; i++) if (life[i] > 0f) return true;
        return false;
    }

    void draw(Canvas c, Paint p) {
        for (int i = 0; i < cap; i++) {
            if (life[i] <= 0f) continue;
            float k = life[i] / maxLife[i];
            switch (type[i]) {
                case SPARK:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeWidth(size[i] * k + 0.5f);
                    p.setColor(color[i]);
                    p.setAlpha((int) (255 * Math.min(1f, k * 1.6f)));
                    c.drawLine(x[i], y[i], x[i] - vx[i] * 0.04f, y[i] - vy[i] * 0.04f, p);
                    break;
                case SMOKE:
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(color[i]);
                    p.setAlpha((int) (110 * k));
                    c.drawCircle(x[i], y[i], size[i], p);
                    break;
                default:
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(color[i]);
                    p.setAlpha((int) (255 * k));
                    c.drawCircle(x[i], y[i], size[i] * (0.4f + 0.6f * k), p);
                    break;
            }
        }
        p.setAlpha(255);
        p.setStyle(Paint.Style.FILL);
    }
}
