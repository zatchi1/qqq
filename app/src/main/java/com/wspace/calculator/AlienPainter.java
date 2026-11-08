package com.wspace.calculator;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Draws the cartoon green alien in "unit space": body is an ellipse with half-width 1 and
 * half-height 1.2 centred on the origin. No neck, no visible arms, two short legs, a big glossy
 * sky-blue visor on the front of the head. Shading gives it a 3-D toy look.
 */
final class AlienPainter {

    final Path bodyPath = new Path();

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Shader bodyShader;
    private final Shader visorShader;
    private final Shader legShader;

    AlienPainter() {
        bodyPath.addOval(-1f, -1.2f, 1f, 1.2f, Path.Direction.CW);
        bodyShader = new RadialGradient(-0.4f, -0.6f, 2.0f,
                new int[]{0xFFC8FF95, 0xFF5EDB5C, 0xFF22A046, 0xFF0C5A2A},
                new float[]{0f, 0.38f, 0.75f, 1f}, Shader.TileMode.CLAMP);
        visorShader = new LinearGradient(-0.6f, -0.8f, 0.6f, 0.3f,
                new int[]{0xFFE6FBFF, 0xFF6FD8FF, 0xFF1E9BE0, 0xFF0A5C9E},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP);
        legShader = new LinearGradient(0f, 0f, 0f, 0.55f, 0xFF3FBF55, 0xFF0F6B2D, Shader.TileMode.CLAMP);
    }

    /** Convenience: transforms to screen space then draws. halfW = on-screen half width in px. */
    void draw(Canvas c, float cx, float cy, float halfW, float tiltDeg, float t, float yaw) {
        c.save();
        c.translate(cx, cy);
        c.rotate(tiltDeg);
        c.scale(halfW, halfW);
        drawUnit(c, t, yaw);
        c.restore();
    }

    /**
     * @param t   animation time in seconds (legs sway, blink)
     * @param yaw -1..1: 0 = facing the viewer, 1 = side view looking right (visor moves to the edge)
     */
    void drawUnit(Canvas c, float t, float yaw) {
        float ay = Math.abs(yaw);

        // ---- legs (short, swaying) ----
        float sway = (float) Math.sin(t * 3.2f) * 8f;
        for (int i = 0; i < 2; i++) {
            float sx = (i == 0 ? -0.42f : 0.42f) * (1f - 0.35f * ay) + yaw * 0.12f;
            c.save();
            c.translate(sx, 1.0f);
            c.rotate(i == 0 ? sway : -sway);
            r.set(-0.2f, 0f, 0.2f, 0.55f);
            p.setStyle(Paint.Style.FILL);
            p.setShader(legShader);
            c.drawRoundRect(r, 0.2f, 0.2f, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.045f);
            p.setColor(0xFF0B4A22);
            c.drawRoundRect(r, 0.2f, 0.2f, p);
            c.restore();
        }

        // ---- body ----
        p.setStyle(Paint.Style.FILL);
        p.setShader(bodyShader);
        c.drawPath(bodyPath, p);
        p.setShader(null);

        // darker skin spots
        p.setColor(0x3300501A);
        r.set(-0.76f, 0.4f, -0.48f, 0.6f);
        c.drawOval(r, p);
        r.set(0.45f, 0.68f, 0.65f, 0.82f);
        c.drawOval(r, p);
        r.set(0.58f, 0.15f, 0.72f, 0.27f);
        c.drawOval(r, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.05f);
        p.setColor(0xFF0B4A22);
        c.drawPath(bodyPath, p);

        // rim light (cyan) on the right edge
        r.set(-1f, -1.2f, 1f, 1.2f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(0.05f);
        p.setColor(0xAA7EF7FF);
        c.drawArc(r, -40f, 80f, false, p);

        // ---- visor ----
        float vx = yaw * 0.32f;
        float vw = 0.78f * (1f - 0.28f * ay);
        float vh = 0.6f;
        float vy = -0.3f;
        r.set(vx - vw, vy - vh, vx + vw, vy + vh);
        p.setStyle(Paint.Style.FILL);
        p.setShader(visorShader);
        c.drawOval(r, p);
        p.setShader(null);

        // eyes behind the glass
        float blinkCycle = (t / 3.4f) % 1f;
        float blink = blinkCycle < 0.06f ? 0.12f : 1f;
        float eyeSpread = 0.28f * (vw / 0.78f);
        p.setColor(0xFF0B1B2E);
        for (int i = 0; i < 2; i++) {
            float ex = vx + (i == 0 ? -eyeSpread : eyeSpread);
            r.set(ex - 0.11f, -0.22f - 0.17f * blink, ex + 0.11f, -0.22f + 0.17f * blink);
            c.drawOval(r, p);
        }
        if (blink > 0.5f) {
            p.setColor(0xFFFFFFFF);
            for (int i = 0; i < 2; i++) {
                float ex = vx + (i == 0 ? -eyeSpread : eyeSpread);
                c.drawCircle(ex - 0.03f, -0.27f, 0.035f, p);
            }
        }
        // smile
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.04f);
        p.setColor(0xFF0B1B2E);
        float sw = 0.2f * (vw / 0.78f);
        r.set(vx - sw, -0.2f, vx + sw, 0.06f);
        c.drawArc(r, 25f, 130f, false, p);

        // glass frame
        r.set(vx - vw, vy - vh, vx + vw, vy + vh);
        p.setStrokeWidth(0.08f);
        p.setColor(0xFF0E4D78);
        c.drawOval(r, p);
        p.setStrokeWidth(0.02f);
        p.setColor(0x99BFF3FF);
        r.set(vx - vw + 0.05f, vy - vh + 0.05f, vx + vw - 0.05f, vy + vh - 0.05f);
        c.drawOval(r, p);

        // glare streak + dot
        p.setStrokeWidth(0.07f);
        p.setColor(0x99FFFFFF);
        r.set(vx - vw * 0.82f, vy - vh * 0.82f, vx + vw * 0.82f, vy + vh * 0.82f);
        c.drawArc(r, 200f, 55f, false, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xB4FFFFFF);
        c.drawCircle(vx + vw * 0.55f, vy - vh * 0.45f, 0.045f, p);
    }
}
