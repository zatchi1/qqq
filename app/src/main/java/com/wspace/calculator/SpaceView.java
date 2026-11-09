package com.wspace.calculator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Animated pixel-art night sky behind the calculator: deep purple galaxy clouds, twinkling stars,
 * a ringed planet and a big blue planet that float slowly, a small rocket cruising with a flickering
 * flame, and an occasional shooting star. About 25 redraws per second, no allocation while drawing.
 */
final class SpaceView extends View {

    private static final int STAR_COUNT = 56;
    private static final int[] STAR_COLORS = {0xFF8B93C8, 0xFFB5BCEE, 0xFFFFFFFF, 0xFF9BE8DE, 0xFFFFE9A0};
    private static final int FRAME_MS = 40;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bp = new Paint();
    private final float[] sx = new float[STAR_COUNT], sy = new float[STAR_COUNT];
    private final float[] phase = new float[STAR_COUNT], speed = new float[STAR_COUNT];
    private final int[] col = new int[STAR_COUNT];
    private final boolean[] big = new boolean[STAR_COUNT];
    private final float unit;
    private boolean running = true;

    // clouds: centre x, y (fraction of screen), radius (fraction of width), drift speed, phase
    private static final float[][] CLOUD = {
            {0.20f, 0.18f, 0.90f, 0.11f, 0.0f},
            {0.85f, 0.40f, 1.00f, 0.09f, 2.1f},
            {0.30f, 0.80f, 0.85f, 0.13f, 4.2f}};
    private static final int[] CLOUD_COLOR = {0x7A5A2FE0, 0x663A2FB8, 0x5CB83C9A};
    private final Paint[] cloudPaint = new Paint[CLOUD.length];

    private Bitmap ringed, bluePlanet, moon, rocket;

    private static final String[] ROCKET = {
            "....OO..GGGGGg....",
            "...OOOGGGGGGGGgg..",
            "..OOOGGGBBBGGGGgRR",
            "....OGGGBwBGGGGgRR",
            "....OGGGBBBGGGGgRR",
            "..OOOGGGGGGGGGgg..",
            "...OOOGGGGGGGGg...",
            "....OO..GGGGGg...."};

    SpaceView(Context ctx) {
        super(ctx);
        unit = Math.max(2f, Math.round(ctx.getResources().getDisplayMetrics().density * 1.5f));
    }

    void setRunning(boolean r) {
        running = r;
        if (r) postInvalidateOnAnimation();
    }

    // ------------------------------------------------------------------ sprites
    /** Pixel sphere with 3-tone shading, optional tilted ring (its back half hides behind the planet). */
    private static Bitmap planet(int cells, float cell, int base, int light, int dark, boolean ring) {
        int bw = Math.round(cells * cell * (ring ? 1.9f : 1f)), bh = Math.round(cells * cell * (ring ? 1.25f : 1f));
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint q = new Paint();
        float cx = bw / 2f, cy = bh / 2f, rad = cells * cell / 2f;
        float ra = rad * 1.75f, rb = rad * 0.42f;
        double ang = -0.35, ca = Math.cos(ang), sa = Math.sin(ang);
        int nx = (int) Math.ceil(bw / cell), ny = (int) Math.ceil(bh / cell);
        for (int gy = 0; gy < ny; gy++) {
            for (int gx = 0; gx < nx; gx++) {
                float x = (gx + 0.5f) * cell - cx, y = (gy + 0.5f) * cell - cy;
                float d2 = (x * x + y * y) / (rad * rad);
                boolean onPlanet = d2 <= 1f;
                int color = 0;
                if (onPlanet) {
                    float dx = x / rad, dy = y / rad, z = (float) Math.sqrt(1f - d2);
                    float lam = -0.45f * dx - 0.55f * dy + 0.7f * z;
                    color = lam > 0.62f ? light : lam > 0.22f ? base : dark;
                }
                if (ring) {
                    double rx = x * ca + y * sa, ry = -x * sa + y * ca;
                    double e = (rx / ra) * (rx / ra) + (ry / rb) * (ry / rb);
                    if (e >= 0.58 && e <= 1.0 && (ry > 0 || !onPlanet)) {
                        color = e > 0.8 ? 0xFFB8A0F0 : 0xFF8F78D8;
                    }
                }
                if (color != 0) {
                    q.setColor(color);
                    cv.drawRect(gx * cell, gy * cell, (gx + 1) * cell, (gy + 1) * cell, q);
                }
            }
        }
        return bmp;
    }

    private static Bitmap rocketBitmap(float cell) {
        int w = Math.round(ROCKET[0].length() * cell), h = Math.round(ROCKET.length * cell);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint q = new Paint();
        for (int y = 0; y < ROCKET.length; y++) {
            for (int x = 0; x < ROCKET[y].length(); x++) {
                int c;
                switch (ROCKET[y].charAt(x)) {
                    case 'G': c = 0xFFB9C4F0; break;
                    case 'g': c = 0xFF7882C8; break;
                    case 'B': c = 0xFF59E0FF; break;
                    case 'w': c = 0xFFFFFFFF; break;
                    case 'R': c = 0xFFFF5A5A; break;
                    case 'O': c = 0xFFFF9A3C; break;
                    default: continue;
                }
                q.setColor(c);
                cv.drawRect(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell, q);
            }
        }
        return bmp;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        Random rnd = new Random(11);
        for (int i = 0; i < STAR_COUNT; i++) {
            sx[i] = Math.round(rnd.nextFloat() * w / unit) * unit;
            sy[i] = Math.round(rnd.nextFloat() * h / unit) * unit;
            phase[i] = rnd.nextFloat() * 6.28f;
            speed[i] = 0.6f + rnd.nextFloat() * 1.4f;
            col[i] = STAR_COLORS[rnd.nextInt(STAR_COLORS.length)];
            big[i] = i % 5 == 0;
        }
        for (int i = 0; i < CLOUD.length; i++) {
            float r = Math.max(1f, w * CLOUD[i][2]);
            int c = CLOUD_COLOR[i];
            RadialGradient g = new RadialGradient(0f, 0f, r,
                    new int[]{c, (c & 0x00FFFFFF) | ((c >>> 24) / 3 << 24), c & 0x00FFFFFF},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
            cp.setShader(g);
            cloudPaint[i] = cp;
        }
        float cell = Math.max(3f, Math.round(w / 140f));
        ringed = planet(14, cell, 0xFF8A55D8, 0xFFB98CF5, 0xFF5A33A0, true);
        bluePlanet = planet(30, cell, 0xFF4A56C8, 0xFF7C88F0, 0xFF2A2F80, false);
        moon = planet(6, cell, 0xFF8A55D8, 0xFFB98CF5, 0xFF5A33A0, false);
        rocket = rocketBitmap(cell);
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(0xFF06061A);
        float t = SystemClock.uptimeMillis() / 1000f;

        for (int i = 0; i < CLOUD.length; i++) {
            if (cloudPaint[i] == null) continue;
            float[] k = CLOUD[i];
            float cx = w * k[0] + (float) Math.sin(t * k[3] + k[4]) * w * 0.07f;
            float cy = h * k[1] + (float) Math.cos(t * k[3] * 0.8f + k[4]) * h * 0.03f;
            c.save();
            c.translate(cx, cy);
            c.drawCircle(0f, 0f, Math.max(1f, w * k[2]), cloudPaint[i]);
            c.restore();
        }

        // twinkling stars (smooth fade, some with a plus-shaped sparkle)
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < STAR_COUNT; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(t * speed[i] + phase[i]);
            int a = (int) (45 + 210 * tw * tw);
            p.setColor(Color.argb(a, Color.red(col[i]), Color.green(col[i]), Color.blue(col[i])));
            c.drawRect(sx[i], sy[i], sx[i] + unit, sy[i] + unit, p);
            if (big[i] && tw > 0.55f) {
                float arm = unit * (1f + 2f * (tw - 0.55f) / 0.45f);
                p.setColor(Color.argb((int) (170 * (tw - 0.55f) / 0.45f), 255, 255, 255));
                c.drawRect(sx[i] - arm, sy[i], sx[i] + unit + arm, sy[i] + unit, p);
                c.drawRect(sx[i], sy[i] - arm, sx[i] + unit, sy[i] + unit + arm, p);
            }
        }

        if (moon != null) c.drawBitmap(moon, w * 0.43f, h * 0.012f + (float) Math.sin(t * 0.7f) * h * 0.003f, bp);
        if (bluePlanet != null) {
            c.drawBitmap(bluePlanet, w * 0.80f, h * 0.155f + (float) Math.sin(t * 0.35f + 1f) * h * 0.004f, bp);
        }
        if (ringed != null) {
            c.drawBitmap(ringed, w * 0.76f - ringed.getWidth() * 0.15f, h * 0.045f + (float) Math.sin(t * 0.45f) * h * 0.004f, bp);
        }
        drawRocket(c, w, h, t);
        drawShootingStar(c, w, h, t);

        if (running) postInvalidateDelayed(FRAME_MS);
    }

    private void drawRocket(Canvas c, int w, int h, float t) {
        if (rocket == null) return;
        float x = w * 0.16f + (float) Math.sin(t * 0.30f) * w * 0.03f;
        float y = h * 0.125f + (float) Math.sin(t * 0.55f) * h * 0.008f;
        c.save();
        c.translate(x, y);
        c.rotate(-32f);
        // flickering flame behind the rocket
        float cell = rocket.getWidth() / (float) ROCKET[0].length();
        float fl = 3f + 2f * (float) Math.abs(Math.sin(t * 22f));
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFEB5A);
        c.drawRect(-fl * cell, 3 * cell, 3 * cell, 5 * cell, p);
        p.setColor(0xFFFF8C28);
        c.drawRect(-fl * cell * 0.6f, 3.4f * cell, 3 * cell, 4.6f * cell, p);
        c.drawBitmap(rocket, 0f, 0f, bp);
        c.restore();
    }

    /** A streak of light every ~9 s, sweeping across the upper sky for about 0.7 s. */
    private void drawShootingStar(Canvas c, int w, int h, float t) {
        float cycle = t % 9f;
        if (cycle > 0.7f) return;
        float k = cycle / 0.7f;
        float x = w * (1.0f - 0.9f * k), y = h * (0.04f + 0.16f * k);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 8; i++) {
            float a = (1f - i / 8f) * (1f - k * 0.6f);
            p.setColor(Color.argb((int) (230 * a), 255, 255, 255));
            float s = unit * (2.2f - i * 0.18f);
            c.drawRect(x + i * unit * 2.4f, y - i * unit * 1.1f, x + i * unit * 2.4f + s, y - i * unit * 1.1f + s, p);
        }
    }
}
