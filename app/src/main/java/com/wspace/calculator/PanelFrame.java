package com.wspace.calculator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * Sci-fi metal frame behind the display and keypad: stepped corners, bevelled purple-steel border,
 * rivets, glowing side lights that pulse in turn and a teal status bar at the bottom.
 */
final class PanelFrame extends View {

    private final Paint p = new Paint();
    private final float density;
    private float l, t, r, b;
    private boolean has = false;
    private boolean running = true;

    PanelFrame(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
    }

    /** Frame bounds in this view's coordinates. */
    void setRect(float left, float top, float right, float bottom) {
        l = left; t = top; r = right; b = bottom;
        has = true;
        invalidate();
    }

    void setRunning(boolean on) {
        running = on;
        if (on) postInvalidateOnAnimation();
    }

    private void stepped(Canvas c, float x0, float y0, float x1, float y1, float u, int color) {
        p.setColor(color);
        c.drawRect(x0 + 2 * u, y0, x1 - 2 * u, y1, p);
        c.drawRect(x0 + u, y0 + u, x1 - u, y1 - u, p);
        c.drawRect(x0, y0 + 2 * u, x1, y1 - 2 * u, p);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!has) return;
        float u = Math.max(2f, Math.round(density * 2f));
        float w = r - l, h = b - t;
        p.setStyle(Paint.Style.FILL);
        float time = SystemClock.uptimeMillis() / 1000f;

        stepped(c, l, t, r, b, u * 2, 0xFF05050F);                               // outline
        stepped(c, l + u, t + u, r - u, b - u, u * 2, 0xFF4A4390);                // steel
        stepped(c, l + 2 * u, t + 2 * u, r - 2 * u, b - 2 * u, u * 2, 0xFF2B2763);// shade
        stepped(c, l + 5 * u, t + 5 * u, r - 5 * u, b - 5 * u, u * 2, 0xFF05050F);// inner edge
        stepped(c, l + 6 * u, t + 6 * u, r - 6 * u, b - 6 * u, u * 2, 0xF2080818);// dark interior
        // top bevel highlight
        p.setColor(0xFF7A72D0);
        c.drawRect(l + 5 * u, t + u, r - 5 * u, t + 2 * u, p);

        // rivets in the four corners
        float[][] rv = {{l + 3 * u, t + 4 * u}, {r - 3 * u, t + 4 * u}, {l + 3 * u, b - 4 * u}, {r - 3 * u, b - 4 * u}};
        for (float[] q : rv) {
            p.setColor(0xFF161236);
            c.drawRect(q[0] - u, q[1] - u, q[0] + u, q[1] + u, p);
            p.setColor(0xFF9890F0);
            c.drawRect(q[0] - u, q[1] - u, q[0], q[1], p);
        }

        // side lights: a row of small lamps running down both sides, pulsing in sequence
        int lamps = 6;
        for (int i = 0; i < lamps; i++) {
            float y = t + h * (0.18f + 0.64f * i / (lamps - 1));
            float k = 0.5f + 0.5f * (float) Math.sin(time * 2.4f - i * 0.9f);
            int col = i % 2 == 0 ? 0xFF3CFFC0 : 0xFF59B8FF;
            p.setColor((col & 0x00FFFFFF) | ((int) (70 + 185 * k) << 24));
            c.drawRect(l + 2 * u, y - u, l + 4 * u, y + u, p);
            c.drawRect(r - 4 * u, y - u, r - 2 * u, y + u, p);
        }

        // bottom status bar
        float cx = (l + r) / 2f;
        float k = 0.55f + 0.45f * (float) Math.sin(time * 1.8f);
        p.setColor(0xFF0B1E2A);
        c.drawRect(cx - 14 * u, b - 5 * u, cx + 14 * u, b - 2 * u, p);
        p.setColor((0x3CFFD8 & 0x00FFFFFF) | ((int) (120 + 135 * k) << 24));
        c.drawRect(cx - 12 * u, b - 4.5f * u, cx + 12 * u, b - 2.5f * u, p);

        if (running) postInvalidateDelayed(50);
    }
}
