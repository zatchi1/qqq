package com.wspace.calculator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Random;

/**
 * Transparent overlay (never takes touches) with two characters:
 *  - the big one peeking over the top edge of the display, rocking gently, paws on the edge and a
 *    blinking "!!" beside it (drawn from the uploaded picture);
 *  - a tiny pixel one that floats weightlessly between the keys, drifting on a slow looping path,
 *    tilting with its movement, leaving a trail of sparkles and hopping when a key is pressed.
 */
final class CharacterView extends View {

    private static final int TRAIL = 14;

    private final Paint p = new Paint();
    private final Paint bmpP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final Random rnd = new Random();
    private final float density;
    private final float s;                       // size of one tiny-sprite pixel in px

    // area the tiny character floats in (the keypad)
    private float aL, aT, aR, aB;
    private boolean hasArea = false;

    // peeking character
    private Bitmap peek;
    private float peekCx, peekTop, peekW;
    private boolean hasPeek = false;

    private float clock = 0f;
    private float hopT = -1f;
    private float fx, fy, prevFx, prevFy;
    private float tilt = 0f;
    private boolean faceLeft = false;

    private final float[] trX = new float[TRAIL], trY = new float[TRAIL], trLife = new float[TRAIL];
    private int trNext = 0;
    private float trTimer = 0f;

    private long lastNs = 0;
    private boolean running = true;

    CharacterView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        s = Math.max(2f, Math.round(density * 1.5f));
        setClickable(false);
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            peek = BitmapFactory.decodeResource(getResources(), R.drawable.peek_char, o);
        } catch (Throwable ignored) {
            peek = null;
        }
    }

    /** Keypad rectangle in this view's coordinates: the tiny character floats inside it. */
    void setFloatArea(float l, float t, float r, float b) {
        aL = l; aT = t; aR = r; aB = b;
        hasArea = true;
    }

    /** The big character sits with its body behind the display; cx = centre, top = display's top edge. */
    void setPeek(float cx, float displayTop, float width) {
        // never taller than the room above the display (otherwise the head would be cut off by the
        // top of the screen on phones where that gap is small)
        if (peek != null) {
            float ratio = peek.getHeight() / (float) peek.getWidth();
            float room = displayTop - density * 13f;                 // bobbing + hop + a little margin
            float maxByHeight = room / (0.62f * ratio);
            if (maxByHeight > density * 40f) width = Math.min(width, maxByHeight);
            else width = Math.min(width, density * 40f);
        }
        peekCx = cx;
        peekTop = displayTop;
        peekW = width;
        hasPeek = peek != null && displayTop > 0f;
    }

    void setRunning(boolean r) {
        running = r;
        if (r) {
            lastNs = 0;
            postInvalidateOnAnimation();
        }
    }

    /** A key was pressed: both characters hop. */
    void hop() { if (hopT < 0f) hopT = 0f; }

    // ------------------------------------------------------------------ motion
    private void update(float dt) {
        clock += dt;
        if (hopT >= 0f) {
            hopT += dt / 0.3f;
            if (hopT >= 1f) hopT = -1f;
        }
        if (hasArea) {
            // two slow sines per axis = a lazy looping path that covers most of the keypad
            float w = aR - aL, h = aB - aT;
            float nx = 0.5f + 0.40f * (float) Math.sin(clock * 0.33f) + 0.10f * (float) Math.sin(clock * 0.91f + 1f);
            float ny = 0.5f + 0.38f * (float) Math.sin(clock * 0.24f + 0.6f) + 0.10f * (float) Math.sin(clock * 0.67f);
            prevFx = fx; prevFy = fy;
            fx = aL + w * nx;
            fy = aT + h * ny;
            float vx = (fx - prevFx) / Math.max(dt, 0.001f);
            if (Math.abs(vx) > 4f) faceLeft = vx < 0f;
            float target = Math.max(-10f, Math.min(10f, vx * 0.05f));
            tilt += (target - tilt) * Math.min(1f, dt * 4f);

            trTimer -= dt;
            if (trTimer <= 0f) {
                trTimer = 0.07f;
                trX[trNext] = fx + (rnd.nextFloat() - 0.5f) * s * 4f;
                trY[trNext] = fy + (rnd.nextFloat() - 0.2f) * s * 5f;
                trLife[trNext] = 1f;
                trNext = (trNext + 1) % TRAIL;
            }
        }
        for (int i = 0; i < TRAIL; i++) if (trLife[i] > 0f) trLife[i] -= dt * 1.1f;
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        if (running) update(dt);

        if (hasPeek) drawPeek(c);
        if (hasArea) drawFloater(c);
        if (running) postInvalidateOnAnimation();
    }

    private void drawFloater(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < TRAIL; i++) {                     // sparkle trail
            if (trLife[i] <= 0f) continue;
            int a = Math.max(0, Math.min(255, (int) (200 * trLife[i])));
            p.setColor((i % 2 == 0 ? 0x008DFFB0 : 0x007DF9FF) | (a << 24));
            float z = s * (0.6f + trLife[i]);
            c.drawRect(trX[i], trY[i], trX[i] + z, trY[i] + z, p);
        }
        float hop = hopT >= 0f ? 4f * 10f * density * hopT * (1f - hopT) : 0f;
        float bob = (float) Math.sin(clock * 2.1f) * s * 1.6f;
        c.save();
        c.translate(fx, fy - hop + bob);
        c.rotate(tilt + (float) Math.sin(clock * 1.3f) * 3f);
        // feet dangling (step 2), no walking: it drifts like it is weightless
        Sprite.draw(c, p, Math.round(-SpriteData.W * s / 2f), Math.round(-SpriteData.H * s / 2f), s, faceLeft, 2);
        c.restore();
    }

    private void drawPeek(Canvas c) {
        float sc = peekW / peek.getWidth();
        float bw = peek.getWidth() * sc, bh = peek.getHeight() * sc;
        float rock = (float) Math.sin(clock * 1.1f);                      // gentle left-right rocking
        float bob = (float) Math.sin(clock * 1.7f) * density * 3f;
        float hopUp = hopT >= 0f ? 4f * 8f * density * hopT * (1f - hopT) : 0f;
        float top = peekTop - bh * 0.62f + bob - hopUp;                   // ~62% of the body shows above the display

        c.save();
        c.clipRect(0f, 0f, getWidth(), peekTop + density * 3f);
        c.rotate(rock * 2.4f, peekCx, peekTop);
        dst.set(peekCx - bw / 2f, top, peekCx + bw / 2f, top + bh);
        c.drawBitmap(peek, null, dst, bmpP);
        c.restore();

        // two paws gripping the edge of the display (they tap alternately)
        float pu = Math.max(2f, Math.round(density * 2f));
        float pawW = bw * 0.20f;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 2; i++) {
            float px = peekCx + (i == 0 ? -bw * 0.34f : bw * 0.26f) + rock * pu;
            float lift = Math.max(0f, (float) Math.sin(clock * 2.6f + i * Math.PI)) * pu * 2f;
            float y1 = peekTop + pu * 2f, y0 = y1 - pu * 7f - lift;
            stepped(c, px - pawW / 2f - pu, y0 - pu, px + pawW / 2f + pu, y1 + pu, pu, 0xFF14141E);
            stepped(c, px - pawW / 2f, y0, px + pawW / 2f, y1, pu, 0xFF92C673);
            p.setColor(0xFF6FA855);
            c.drawRect(px - pawW / 6f, y0 + pu, px - pawW / 6f + pu, y1 - pu, p);
            c.drawRect(px + pawW / 6f, y0 + pu, px + pawW / 6f + pu, y1 - pu, p);
        }

        // yellow "!!" beside the head, blinking
        if (((int) (clock * 3f)) % 3 != 2) {
            float ex = peekCx + bw * 0.62f, ey = peekTop - bh * 0.42f + bob;
            c.save();
            c.translate(ex, ey);
            c.rotate(-20f);
            p.setColor(0xFF14141E);
            c.drawRect(-pu * 2f, -pu * 8f, pu * 2.5f, pu * 3f, p);
            c.drawRect(-pu * 2f, pu * 5f, pu * 2.5f, pu * 9f, p);
            p.setColor(0xFFFFE23A);
            c.drawRect(-pu, -pu * 7f, pu * 1.5f, pu * 2f, p);
            c.drawRect(-pu, pu * 6f, pu * 1.5f, pu * 8f, p);
            c.restore();
        }
    }

    private void stepped(Canvas c, float l, float t, float r, float b, float u, int color) {
        p.setColor(color);
        c.drawRect(l + u, t, r - u, b, p);
        c.drawRect(l, t + u, r, b - u, p);
    }
}
