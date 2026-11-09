package com.wspace.calculator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Soft night sky: a deep-blue base, three slow drifting colour clouds (purple, teal, pink) and a field
 * of tiny pixel stars that twinkle smoothly. Light on the phone: ~25 redraws per second, no
 * allocations while drawing.
 */
final class SpaceView extends View {

    private static final int STAR_COUNT = 40;
    private static final int[] STAR_COLORS = {0xFF8B93C8, 0xFFB5BCEE, 0xFFFFFFFF, 0xFF9BE8DE};
    private static final int FRAME_MS = 40;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] sx = new float[STAR_COUNT];
    private final float[] sy = new float[STAR_COUNT];
    private final float[] phase = new float[STAR_COUNT];
    private final float[] speed = new float[STAR_COUNT];
    private final int[] col = new int[STAR_COUNT];
    private final boolean[] big = new boolean[STAR_COUNT];
    private final float unit;
    private boolean running = true;

    // clouds: centre (fraction of the screen), radius (fraction of width), colour, drift speed
    private static final float[][] CLOUD = {
            {0.18f, 0.22f, 0.85f, 0.11f, 0.00f},
            {0.85f, 0.55f, 0.95f, 0.09f, 2.10f},
            {0.30f, 0.88f, 0.80f, 0.13f, 4.20f}};
    private static final int[] CLOUD_COLOR = {0x6A4A2FD8, 0x5A1FB6A8, 0x52B83C9A};
    private final Paint[] cloudPaint = new Paint[CLOUD.length];

    SpaceView(Context ctx) {
        super(ctx);
        unit = Math.max(2f, Math.round(ctx.getResources().getDisplayMetrics().density * 1.5f));
    }

    void setRunning(boolean r) {
        running = r;
        if (r) postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        Random rnd = new Random(11);
        for (int i = 0; i < STAR_COUNT; i++) {
            sx[i] = Math.round(rnd.nextFloat() * w / unit) * unit;
            sy[i] = Math.round(rnd.nextFloat() * h / unit) * unit;
            phase[i] = rnd.nextFloat() * 6.28f;
            speed[i] = 0.5f + rnd.nextFloat() * 1.1f;
            col[i] = STAR_COLORS[rnd.nextInt(STAR_COLORS.length)];
            big[i] = i % 6 == 0;
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
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(0xFF070713);
        float t = SystemClock.uptimeMillis() / 1000f;

        // slow drifting colour clouds
        for (int i = 0; i < CLOUD.length; i++) {
            if (cloudPaint[i] == null) continue;
            float[] k = CLOUD[i];
            float cx = w * k[0] + (float) Math.sin(t * k[3] + k[4]) * w * 0.07f;
            float cy = h * k[1] + (float) Math.cos(t * k[3] * 0.8f + k[4]) * h * 0.03f;
            float r = Math.max(1f, w * k[2]);
            c.save();
            c.translate(cx, cy);
            c.drawCircle(0f, 0f, r, cloudPaint[i]);
            c.restore();
        }

        // smooth twinkle
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < STAR_COUNT; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(t * speed[i] + phase[i]);
            int a = (int) (50 + 205 * tw * tw);
            p.setColor(Color.argb(a, Color.red(col[i]), Color.green(col[i]), Color.blue(col[i])));
            c.drawRect(sx[i], sy[i], sx[i] + unit, sy[i] + unit, p);
            if (big[i] && tw > 0.6f) {                                    // tiny plus-shaped sparkle
                p.setColor(Color.argb((int) (150 * (tw - 0.6f) / 0.4f), 255, 255, 255));
                c.drawRect(sx[i] - unit, sy[i], sx[i], sy[i] + unit, p);
                c.drawRect(sx[i] + unit, sy[i], sx[i] + 2 * unit, sy[i] + unit, p);
                c.drawRect(sx[i], sy[i] - unit, sx[i] + unit, sy[i], p);
                c.drawRect(sx[i], sy[i] + unit, sx[i] + unit, sy[i] + 2 * unit, p);
            }
        }
        if (running) postInvalidateDelayed(FRAME_MS);
    }
}
