package com.wspace.calculator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Empty pixel-art night sky: flat dark colour and a handful of tiny, far-away stars that twinkle
 * slowly. Nothing else moves here (no ships, planets or rocks). Redraws only ~8 times a second.
 */
final class SpaceView extends View {

    private static final int STAR_COUNT = 26;
    private static final int[] STAR_COLORS = {0xFF6C73A8, 0xFF9AA2D8, 0xFFFFFFFF, 0xFF7DD3C8};

    private final Paint p = new Paint();
    private final float[] sx = new float[STAR_COUNT];
    private final float[] sy = new float[STAR_COUNT];
    private final float[] phase = new float[STAR_COUNT];
    private final float[] speed = new float[STAR_COUNT];
    private final int[] col = new int[STAR_COUNT];
    private final boolean[] big = new boolean[STAR_COUNT];
    private final float unit;
    private boolean running = true;

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
            speed[i] = 0.4f + rnd.nextFloat() * 1.2f;
            col[i] = STAR_COLORS[rnd.nextInt(STAR_COLORS.length)];
            big[i] = i % 7 == 0;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(0xFF090914);
        float t = SystemClock.uptimeMillis() / 1000f;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < STAR_COUNT; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(t * speed[i] + phase[i]);
            int level = tw > 0.66f ? 3 : tw > 0.33f ? 2 : 1;      // stepped twinkle = pixel feel
            p.setColor(col[i]);
            p.setAlpha(level * 70 + 30);
            c.drawRect(sx[i], sy[i], sx[i] + unit, sy[i] + unit, p);
            if (big[i] && level == 3) {                           // tiny plus-shaped sparkle
                p.setAlpha(110);
                c.drawRect(sx[i] - unit, sy[i], sx[i], sy[i] + unit, p);
                c.drawRect(sx[i] + unit, sy[i], sx[i] + 2 * unit, sy[i] + unit, p);
                c.drawRect(sx[i], sy[i] - unit, sx[i] + unit, sy[i], p);
                c.drawRect(sx[i], sy[i] + unit, sx[i] + unit, sy[i] + 2 * unit, p);
            }
        }
        p.setAlpha(255);
        if (running) postInvalidateDelayed(120);
    }
}
