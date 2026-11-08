package com.wspace.calculator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/**
 * Animated space backdrop: nebula, twinkling stars, planets, asteroids, passing ships, random
 * explosions, wandering mini aliens and the big floating green alien at the top.
 * Everything is drawn with Canvas; no bitmaps, no allocation in the draw loop (except tiny lists).
 */
final class SpaceView extends View {

    private static final class Star { float x, y, size, phase, speed, depth; }
    private static final class Planet {
        float x, y, r, vx; int c1, c2; boolean ring; Shader shader; Shader shade; float tilt;
    }
    private static final class Asteroid {
        float x, y, r, vx, vy, rot, vr; float[] radii = new float[9];
    }
    private static final class Ship {
        float x, y, vx, phase; boolean saucer; float scale;
    }
    private static final class Mini {
        float x, y, vx, vy, size, tilt, vt, t0, yaw;
    }
    private static final class Boom { float x, y, age, max, radius; }

    private final Random rnd = new Random(7);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final ParticleSystem ps = new ParticleSystem(420);
    private final AlienPainter alien = new AlienPainter();

    private final Star[] stars = new Star[130];
    private final Planet[] planets = new Planet[3];
    private final Asteroid[] asteroids = new Asteroid[9];
    private final ArrayList<Ship> ships = new ArrayList<>();
    private final ArrayList<Mini> minis = new ArrayList<>();
    private final ArrayList<Boom> booms = new ArrayList<>();

    private Shader bgShader;
    private Shader nebulaA, nebulaB;

    private boolean running = true;
    private long lastNs = 0;
    private float time = 0f;
    private float shipTimer = 1.5f, boomTimer = 1.0f, miniTimer = 4f;
    private float squish = 0f; // reaction pulse when the alien is tapped

    SpaceView(Context ctx) {
        super(ctx);
        for (int i = 0; i < stars.length; i++) stars[i] = new Star();
        for (int i = 0; i < planets.length; i++) planets[i] = new Planet();
        for (int i = 0; i < asteroids.length; i++) asteroids[i] = new Asteroid();
    }

    void setRunning(boolean r) {
        running = r;
        if (r) {
            lastNs = 0;
            postInvalidateOnAnimation();
        }
    }

    /** Called by the activity for a little reaction when digits are pressed. */
    void bump() { squish = 1f; }

    /** Spawns an extra burst at a fractional screen position (used for scene transitions). */
    void burst(float fx, float fy) {
        spawnBoom(fx * getWidth(), fy * getHeight());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        bgShader = new LinearGradient(0, 0, 0, h,
                new int[]{0xFF010208, 0xFF05061A, 0xFF0B0838, 0xFF04030F},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP);
        nebulaA = new RadialGradient(w * 0.2f, h * 0.18f, w * 0.7f,
                new int[]{0x553A1C9C, 0x00000000}, null, Shader.TileMode.CLAMP);
        nebulaB = new RadialGradient(w * 0.85f, h * 0.34f, w * 0.6f,
                new int[]{0x4400A89A, 0x00000000}, null, Shader.TileMode.CLAMP);

        for (Star s : stars) {
            s.x = rnd.nextFloat() * w;
            s.y = rnd.nextFloat() * h;
            s.depth = 0.3f + rnd.nextFloat() * 0.7f;
            s.size = (0.6f + rnd.nextFloat() * 1.6f) * s.depth * getResources().getDisplayMetrics().density;
            s.phase = rnd.nextFloat() * 6.28f;
            s.speed = 1.5f + rnd.nextFloat() * 3f;
        }

        int[][] cols = {{0xFFFF9A5C, 0xFF8A2B1B}, {0xFF6C8CFF, 0xFF1B2B7A}, {0xFFB07CFF, 0xFF3B1B6A}};
        for (int i = 0; i < planets.length; i++) {
            Planet pl = planets[i];
            pl.r = w * (i == 0 ? 0.13f : i == 1 ? 0.085f : 0.06f);
            pl.x = w * (i == 0 ? 0.12f : i == 1 ? 0.82f : 0.58f);
            pl.y = h * (i == 0 ? 0.30f : i == 1 ? 0.10f : 0.42f);
            pl.vx = (i == 1 ? -1f : 1f) * w * 0.004f * (1 + i);
            pl.c1 = cols[i][0];
            pl.c2 = cols[i][1];
            pl.ring = (i == 1);
            pl.tilt = -18f + i * 14f;
            pl.shader = new RadialGradient(-pl.r * 0.35f, -pl.r * 0.35f, pl.r * 1.6f,
                    new int[]{lighten(pl.c1), pl.c1, pl.c2}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            pl.shade = new RadialGradient(-pl.r * 0.5f, -pl.r * 0.5f, pl.r * 1.9f,
                    new int[]{0x00000000, 0x00000000, 0xCC000008}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        }

        for (Asteroid a : asteroids) resetAsteroid(a, true, w, h);
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 70);
        int g = Math.min(255, ((c >> 8) & 255) + 70);
        int b = Math.min(255, (c & 255) + 70);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void resetAsteroid(Asteroid a, boolean anywhere, int w, int h) {
        a.r = w * (0.012f + rnd.nextFloat() * 0.025f);
        a.x = anywhere ? rnd.nextFloat() * w : -a.r * 2;
        a.y = anywhere ? rnd.nextFloat() * h * 0.7f : rnd.nextFloat() * h * 0.5f;
        a.vx = w * (0.01f + rnd.nextFloat() * 0.03f);
        a.vy = h * (-0.004f + rnd.nextFloat() * 0.012f);
        a.rot = rnd.nextFloat() * 360f;
        a.vr = -40f + rnd.nextFloat() * 80f;
        for (int i = 0; i < a.radii.length; i++) a.radii[i] = 0.7f + rnd.nextFloat() * 0.5f;
    }

    // ------------------------------------------------------------------ update
    private void update(float dt) {
        int w = getWidth(), h = getHeight();
        time += dt;
        if (squish > 0f) squish = Math.max(0f, squish - dt * 3f);

        for (Star s : stars) {
            s.y += dt * 4f * s.depth;
            if (s.y > h) { s.y = 0; s.x = rnd.nextFloat() * w; }
        }
        for (Planet pl : planets) {
            pl.x += pl.vx * dt;
            if (pl.x > w + pl.r * 2) pl.x = -pl.r * 2;
            if (pl.x < -pl.r * 2) pl.x = w + pl.r * 2;
        }
        for (Asteroid a : asteroids) {
            a.x += a.vx * dt;
            a.y += a.vy * dt;
            a.rot += a.vr * dt;
            if (a.x > w + a.r * 2 || a.y < -a.r * 3 || a.y > h) resetAsteroid(a, false, w, h);
        }

        shipTimer -= dt;
        if (shipTimer <= 0f) {
            shipTimer = 3f + rnd.nextFloat() * 3.5f;
            Ship s = new Ship();
            boolean ltr = rnd.nextBoolean();
            s.saucer = rnd.nextBoolean();
            s.scale = w * (0.035f + rnd.nextFloat() * 0.025f);
            s.y = h * (0.05f + rnd.nextFloat() * 0.5f);
            s.vx = (ltr ? 1 : -1) * w * (0.16f + rnd.nextFloat() * 0.14f);
            s.x = ltr ? -s.scale * 3 : w + s.scale * 3;
            s.phase = rnd.nextFloat() * 6f;
            ships.add(s);
        }
        for (int i = ships.size() - 1; i >= 0; i--) {
            Ship s = ships.get(i);
            s.x += s.vx * dt;
            s.y += (float) Math.sin(time * 2f + s.phase) * dt * 10f;
            if (!s.saucer && rnd.nextFloat() < 0.6f) {
                ps.spawn(ParticleSystem.STAR, s.x - Math.signum(s.vx) * s.scale * 1.4f, s.y,
                        -s.vx * 0.1f, 0f, 0.35f, s.scale * 0.22f, 0xFFFFB347);
            }
            if (s.x < -s.scale * 5 || s.x > w + s.scale * 5) ships.remove(i);
        }

        boomTimer -= dt;
        if (boomTimer <= 0f) {
            boomTimer = 1.4f + rnd.nextFloat() * 2.6f;
            spawnBoom(w * (0.08f + rnd.nextFloat() * 0.84f), h * (0.05f + rnd.nextFloat() * 0.55f));
        }
        for (int i = booms.size() - 1; i >= 0; i--) {
            Boom b = booms.get(i);
            b.age += dt;
            if (b.age > b.max) booms.remove(i);
        }

        miniTimer -= dt;
        if (miniTimer <= 0f) {
            miniTimer = 6f + rnd.nextFloat() * 6f;
            Mini m = new Mini();
            boolean ltr = rnd.nextBoolean();
            m.size = w * (0.035f + rnd.nextFloat() * 0.03f);
            m.x = ltr ? -m.size * 2 : w + m.size * 2;
            m.y = h * (0.06f + rnd.nextFloat() * 0.3f);
            m.vx = (ltr ? 1 : -1) * w * (0.05f + rnd.nextFloat() * 0.05f);
            m.vy = h * (-0.01f + rnd.nextFloat() * 0.02f);
            m.vt = -60f + rnd.nextFloat() * 120f;
            m.tilt = rnd.nextFloat() * 40f;
            m.t0 = rnd.nextFloat() * 5f;
            m.yaw = ltr ? 0.8f : -0.8f;
            minis.add(m);
        }
        for (int i = minis.size() - 1; i >= 0; i--) {
            Mini m = minis.get(i);
            m.x += m.vx * dt;
            m.y += m.vy * dt + (float) Math.sin(time * 1.7f + m.t0) * dt * 14f;
            m.tilt += m.vt * dt;
            if (m.x < -m.size * 4 || m.x > w + m.size * 4) minis.remove(i);
        }
        ps.update(dt);
    }

    private void spawnBoom(float x, float y) {
        Boom b = new Boom();
        b.x = x; b.y = y; b.age = 0f; b.max = 0.55f;
        b.radius = getWidth() * (0.05f + rnd.nextFloat() * 0.06f);
        booms.add(b);
        int[] cols = {0xFFFFD34D, 0xFFFF7A3D, 0xFFFFFFFF, 0xFF7DF9FF};
        ps.sparks(x, y, 18, cols[rnd.nextInt(cols.length)], b.radius * 6f);
        ps.sparks(x, y, 8, 0xFFFFFFFF, b.radius * 4f);
        ps.smoke(x, y, 6, b.radius * 0.5f);
    }

    // ------------------------------------------------------------------ draw
    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || bgShader == null) return;

        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        if (running) update(dt);

        // background
        p.setStyle(Paint.Style.FILL);
        p.setShader(bgShader);
        c.drawRect(0, 0, w, h, p);
        p.setShader(nebulaA);
        c.drawRect(0, 0, w, h, p);
        p.setShader(nebulaB);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // stars
        for (Star s : stars) {
            float tw = 0.55f + 0.45f * (float) Math.sin(time * s.speed + s.phase);
            p.setColor(0xFFFFFFFF);
            p.setAlpha((int) (255 * tw * s.depth));
            c.drawCircle(s.x, s.y, s.size, p);
        }
        p.setAlpha(255);

        // planets
        for (Planet pl : planets) drawPlanet(c, pl);

        // asteroids
        for (Asteroid a : asteroids) drawAsteroid(c, a);

        // ships
        for (Ship s : ships) drawShip(c, s);

        // mini aliens
        for (Mini m : minis) {
            alien.draw(c, m.x, m.y, m.size, m.tilt, time + m.t0, m.yaw);
        }

        // explosions flashes (behind particles)
        for (Boom b : booms) {
            float k = b.age / b.max;
            float rr = b.radius * (0.3f + k);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFE9A0);
            p.setAlpha((int) (140 * (1f - k)));
            c.drawCircle(b.x, b.y, rr, p);
            p.setColor(0xFFFF7A3D);
            p.setAlpha((int) (90 * (1f - k)));
            c.drawCircle(b.x, b.y, rr * 1.5f, p);
            p.setAlpha(255);
        }
        ps.draw(c, p);

        drawMainAlien(c, w, h);

        if (running) postInvalidateOnAnimation();
    }

    private void drawPlanet(Canvas c, Planet pl) {
        c.save();
        c.translate(pl.x, pl.y);
        c.rotate(pl.tilt);
        if (pl.ring) { // back half of the ring
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(pl.r * 0.14f);
            p.setColor(0x88E8D6A0);
            rect.set(-pl.r * 1.7f, -pl.r * 0.45f, pl.r * 1.7f, pl.r * 0.45f);
            c.drawArc(rect, 180f, 180f, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setShader(pl.shader);
        c.drawCircle(0, 0, pl.r, p);
        p.setShader(pl.shade);
        c.drawCircle(0, 0, pl.r, p);
        p.setShader(null);
        // bands / craters for a hand-painted look
        p.setColor(0x22000000);
        rect.set(-pl.r * 0.8f, -pl.r * 0.2f, pl.r * 0.8f, pl.r * 0.05f);
        c.drawOval(rect, p);
        p.setColor(0x18FFFFFF);
        c.drawCircle(pl.r * 0.35f, pl.r * 0.35f, pl.r * 0.13f, p);
        if (pl.ring) { // front half of the ring
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(pl.r * 0.14f);
            p.setColor(0xCCF3E3AE);
            rect.set(-pl.r * 1.7f, -pl.r * 0.45f, pl.r * 1.7f, pl.r * 0.45f);
            c.drawArc(rect, 0f, 180f, false, p);
        }
        c.restore();
        p.setStyle(Paint.Style.FILL);
    }

    private void drawAsteroid(Canvas c, Asteroid a) {
        c.save();
        c.translate(a.x, a.y);
        c.rotate(a.rot);
        path.reset();
        int n = a.radii.length;
        for (int i = 0; i < n; i++) {
            double ang = i * Math.PI * 2 / n;
            float rx = (float) Math.cos(ang) * a.r * a.radii[i];
            float ry = (float) Math.sin(ang) * a.r * a.radii[i];
            if (i == 0) path.moveTo(rx, ry); else path.lineTo(rx, ry);
        }
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF6B5F58);
        c.drawPath(path, p);
        p.setColor(0x55FFFFFF);
        c.drawCircle(-a.r * 0.3f, -a.r * 0.3f, a.r * 0.35f, p);
        p.setColor(0x44000000);
        c.drawCircle(a.r * 0.3f, a.r * 0.25f, a.r * 0.25f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(a.r * 0.08f);
        p.setColor(0xFF2E2622);
        c.drawPath(path, p);
        c.restore();
        p.setStyle(Paint.Style.FILL);
    }

    private void drawShip(Canvas c, Ship s) {
        float u = s.scale;
        float dir = Math.signum(s.vx);
        c.save();
        c.translate(s.x, s.y);
        c.scale(dir, 1f);
        p.setStyle(Paint.Style.FILL);
        if (s.saucer) {
            p.setColor(0xFF7FE9FF);
            p.setAlpha(190);
            rect.set(-u * 0.55f, -u * 0.75f, u * 0.55f, u * 0.1f);
            c.drawOval(rect, p);
            p.setAlpha(255);
            p.setColor(0xFF9AA6C6);
            rect.set(-u * 1.3f, -u * 0.25f, u * 1.3f, u * 0.4f);
            c.drawOval(rect, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(u * 0.07f);
            p.setColor(0xFF2A3355);
            c.drawOval(rect, p);
            p.setStyle(Paint.Style.FILL);
            for (int i = -1; i <= 1; i++) {
                boolean on = ((int) (time * 4f) + i) % 2 == 0;
                p.setColor(on ? 0xFFFFE066 : 0xFFFF5C7A);
                c.drawCircle(i * u * 0.7f, u * 0.1f, u * 0.1f, p);
            }
        } else {
            // little rocket
            p.setColor(0xFFE8ECF8);
            rect.set(-u * 1.1f, -u * 0.35f, u * 0.8f, u * 0.35f);
            c.drawRoundRect(rect, u * 0.3f, u * 0.3f, p);
            p.setColor(0xFFFF4D6D);
            path.reset();
            path.moveTo(u * 0.7f, -u * 0.35f);
            path.lineTo(u * 1.4f, 0f);
            path.lineTo(u * 0.7f, u * 0.35f);
            path.close();
            c.drawPath(path, p);
            p.setColor(0xFF4DD2FF);
            c.drawCircle(u * 0.1f, 0f, u * 0.17f, p);
            p.setColor(0xFFFF4D6D);
            path.reset();
            path.moveTo(-u * 0.8f, -u * 0.3f);
            path.lineTo(-u * 1.3f, -u * 0.75f);
            path.lineTo(-u * 0.4f, -u * 0.3f);
            path.close();
            c.drawPath(path, p);
        }
        c.restore();
    }

    private void drawMainAlien(Canvas c, int w, int h) {
        float halfW = Math.min(w * 0.27f, h * 0.115f);
        float cx = w * 0.5f + (float) Math.sin(time * 0.7f) * w * 0.05f;
        float cy = h * 0.2f + (float) Math.sin(time * 1.6f) * h * 0.012f
                + (float) Math.sin(time * 0.55f) * h * 0.008f;
        float tilt = (float) Math.sin(time * 1.1f) * 6f;
        float pop = 1f + 0.12f * (float) Math.sin(squish * Math.PI);

        // soft shadow/glow beneath (it floats)
        float sh = 0.8f + 0.2f * (float) Math.sin(time * 1.6f + 3.14f);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF39FF88);
        p.setAlpha((int) (28 * sh));
        rect.set(cx - halfW * 1.1f, cy + halfW * 1.9f, cx + halfW * 1.1f, cy + halfW * 2.15f);
        c.drawOval(rect, p);
        p.setAlpha((int) (50 * sh));
        rect.set(cx - halfW * 0.7f, cy + halfW * 1.97f, cx + halfW * 0.7f, cy + halfW * 2.08f);
        c.drawOval(rect, p);
        p.setAlpha(255);

        // aura
        p.setColor(0xFF39FF88);
        p.setAlpha(22);
        c.drawCircle(cx, cy, halfW * 1.7f, p);
        p.setAlpha(255);

        alien.draw(c, cx, cy, halfW * pop, tilt, time, 0f);
    }
}
