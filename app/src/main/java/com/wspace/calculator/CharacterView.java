package com.wspace.calculator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Random;

/**
 * Transparent overlay on top of the keypad. The tiny character walks along the top edge of a key row
 * (swapping its feet), stops to stand around, jumps up or down to the neighbouring row, and hops when
 * a key is pressed. It never takes touches, so the keys underneath keep working.
 */
final class CharacterView extends View {

    private static final int WALK = 0, IDLE = 1, JUMP = 2;

    private final Paint p = new Paint();
    private final Random rnd = new Random();
    private final float density;
    private final float s;                       // size of one sprite pixel in px

    private float left, right;                   // horizontal range the character may roam
    private float[] floors = new float[0];       // y of each row's top edge (feet stand here)

    private int state = IDLE;
    private int floor = 0;
    private float x, y;
    private float targetX, timer;
    private boolean faceLeft = false;
    private float stepClock = 0f;

    private float jx0, jy0, jx1, jy1, jT, jDur, jHeight;
    private float hopT = -1f;                    // reaction hop when a key is pressed
    private float tongueClock = 0f;

    private long lastNs = 0;
    private boolean running = true;

    CharacterView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        s = Math.max(2f, Math.round(density * 1.3f));
        setClickable(false);
    }

    /** Called by the activity once the keypad has been laid out. */
    void setStage(float l, float r, float[] floorYs) {
        left = l + SpriteData.W * s / 2f;
        right = r - SpriteData.W * s / 2f;
        floors = floorYs;
        if (floors.length == 0) return;
        if (floor >= floors.length) floor = floors.length - 1;
        if (state == IDLE && x == 0f) {                 // first layout: start on the second row
            floor = Math.min(1, floors.length - 1);
            x = left + (right - left) * 0.3f;
            y = floors[floor];
            timer = 0.8f;
        } else if (state != JUMP) {
            y = floors[floor];
        }
        x = Math.max(left, Math.min(right, x));
    }

    void setRunning(boolean r) {
        running = r;
        if (r) {
            lastNs = 0;
            postInvalidateOnAnimation();
        }
    }

    /** A key was pressed: the character hops on the spot. */
    void hop() { if (hopT < 0f) hopT = 0f; }

    // ------------------------------------------------------------------ behaviour
    private void chooseNext() {
        if (floors.length == 0) return;
        if (rnd.nextFloat() < 0.35f && floors.length > 1) {
            int nf = floor + (rnd.nextBoolean() ? 1 : -1);
            if (nf < 0) nf = 1;
            if (nf >= floors.length) nf = floors.length - 2;
            jx0 = x; jy0 = y;
            jx1 = left + rnd.nextFloat() * (right - left);
            jy1 = floors[nf];
            jHeight = 34f * density;
            jDur = 0.55f;
            jT = 0f;
            faceLeft = jx1 < jx0;
            floor = nf;
            state = JUMP;
        } else {
            float range = right - left;
            float dist = range * (0.2f + rnd.nextFloat() * 0.5f);
            targetX = x + (rnd.nextBoolean() ? dist : -dist);
            if (targetX < left || targetX > right) targetX = x - (targetX - x);
            targetX = Math.max(left, Math.min(right, targetX));
            faceLeft = targetX < x;
            state = WALK;
        }
    }

    private void update(float dt) {
        if (floors.length == 0) return;
        tongueClock += dt;
        if (hopT >= 0f) {
            hopT += dt / 0.28f;
            if (hopT >= 1f) hopT = -1f;
        }
        switch (state) {
            case WALK: {
                float speed = 52f * density;
                float step = speed * dt;
                float d = targetX - x;
                stepClock += dt;
                if (Math.abs(d) <= step) {
                    x = targetX;
                    state = IDLE;
                    timer = 0.7f + rnd.nextFloat() * 1.8f;
                } else {
                    x += Math.signum(d) * step;
                }
                break;
            }
            case JUMP: {
                jT += dt / jDur;
                float k = Math.min(1f, jT);
                x = jx0 + (jx1 - jx0) * k;
                y = jy0 + (jy1 - jy0) * k - 4f * jHeight * k * (1f - k);
                if (jT >= 1f) {
                    x = jx1; y = jy1;
                    state = IDLE;
                    timer = 0.5f + rnd.nextFloat() * 1.2f;
                }
                break;
            }
            default: {
                timer -= dt;
                if (timer <= 0f) chooseNext();
            }
        }
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        if (running) update(dt);

        if (floors.length > 0) {
            float hop = hopT >= 0f ? 4f * 10f * density * hopT * (1f - hopT) : 0f;
            int step;
            float bob = 0f;
            if (state == WALK) {
                step = ((int) (stepClock / 0.16f)) % 2;
                bob = step == 0 ? 0f : -s;                       // little bounce on every step
            } else if (state == JUMP) {
                step = 2;                                        // feet tucked: both lowered
            } else {
                step = 2;
                bob = 0f;
            }
            // calm weightless float: a slow sine drift on top of whatever it is doing
            float drift = (float) Math.sin(tongueClock * 2.3f) * s * 1.1f - s * 1.1f;
            float top = y - (SpriteData.H + 2) * s - hop + bob + drift;
            float lx = x - SpriteData.W * s / 2f;
            Sprite.draw(c, p, Math.round(lx), Math.round(top), s, faceLeft, step);
        }
        if (running) postInvalidateOnAnimation();
    }
}
