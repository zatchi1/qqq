package com.wspace.calculator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/**
 * Futuristic calculator screen.
 *
 * Normal mode: two lines of cartoon neon digits (small expression line + big line), each new digit
 * pops in with an overshoot.
 *
 * Kill mode ({@link #startSlice}): every character becomes a little green alien, panics, then is
 * genuinely cut in two. Each glyph is rendered to a bitmap, split along a diagonal line into a TOP
 * half and a BOTTOM half bitmap, red flesh + a small white bone are painted on the cut faces, and
 * the two halves then fall away with different rotations while smoke and sparks fly.
 */
final class DisplayView extends View {

    private static final GlyphStyle ALERT = new GlyphStyle(0xFFFFF2A0, 0xFFFF5C5C, 0xFF5A0A0A, 0xFFFF3030);

    // timeline of the kill scene (seconds)
    private static final float T_ALIEN = 0.35f;     // digit morphs into an alien
    private static final float T_PANIC = 0.45f;     // alien notices danger
    private static final float T_CUT = 0.85f;       // first cut
    private static final float CUT_SPREAD = 0.55f;  // cuts are spread across this window
    private static final float T_FALL = 1.15f;      // fall + fade after a cut

    private static final class G {
        char ch;
        float cx, baseline, size;
        int birthIndex = -1;            // index into bigBirth for pop animation, -1 = small line
        float delay;                    // kill scene: extra delay before this glyph is cut
        float angle;                    // cut line angle (radians)
        Bitmap whole, top, bottom;
        float pivotX, pivotY;           // bitmap-space point on the cut line, centre of rotation
        boolean fxDone;
        float jitter;                   // per-glyph random phase
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textP = new Paint();
    private final Paint bmpP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Random rnd = new Random();
    private final ParticleSystem ps = new ParticleSystem(300);
    private final float density;

    private String small = "";
    private String big = "";
    private String prevBig = "";
    private final long[] bigBirth = new long[64];

    private boolean slicing = false;
    private long sliceStartMs;
    private long lastNs;
    private ArrayList<G> glyphs = new ArrayList<>();
    private Runnable onSliceDone;
    private float sliceTotal;

    DisplayView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        GlyphStyle.setup(textP);
    }

    // -------------------------------------------------------------- public API
    /** Sets both lines. popAll = every character of the big line pops (e.g. after "="). */
    void setTexts(String smallLine, String bigLine, boolean popAll) {
        long now = SystemClock.uptimeMillis();
        if (bigLine.length() > bigBirth.length) bigLine = bigLine.substring(bigLine.length() - bigBirth.length);
        for (int i = 0; i < bigLine.length(); i++) {
            boolean changed = i >= prevBig.length() || prevBig.charAt(i) != bigLine.charAt(i);
            if (popAll || changed) bigBirth[i] = now;
        }
        small = smallLine;
        big = bigLine;
        prevBig = bigLine;
        invalidate();
    }

    boolean isEmpty() { return small.isEmpty() && big.isEmpty(); }

    boolean isSlicing() { return slicing; }

    /** Plays the digit-killing scene; onDone runs after everything has fallen and the screen is empty. */
    void startSlice(Runnable onDone) {
        ArrayList<G> list = buildLayout();
        if (list.isEmpty() || getWidth() == 0) {
            small = ""; big = ""; prevBig = "";
            invalidate();
            if (onDone != null) onDone.run();
            return;
        }
        releaseBitmaps();
        glyphs = list;
        int n = glyphs.size();
        for (int i = 0; i < n; i++) {
            G g = glyphs.get(i);
            g.delay = n > 1 ? CUT_SPREAD * i / (n - 1) : 0f;
            g.angle = -0.42f + rnd.nextFloat() * 0.22f;
            g.jitter = rnd.nextFloat() * 6.28f;
            buildBitmaps(g);
        }
        sliceTotal = T_CUT + CUT_SPREAD + T_FALL + 0.1f;
        onSliceDone = onDone;
        sliceStartMs = SystemClock.uptimeMillis();
        lastNs = 0;
        slicing = true;
        invalidate();
    }

    // -------------------------------------------------------------- layout
    private static float ratio(char c) {
        if (c == '.') return 0.34f;
        if (c >= '0' && c <= '9') return 0.62f;
        if (c == 'E') return 0.64f;
        if (c == 'R' || c == 'O') return 0.68f;
        return 0.72f;
    }

    private void layoutLine(String s, float maxSize, float baseline, int birthMode, ArrayList<G> out) {
        if (s.isEmpty()) return;
        float innerW = getWidth() - 36f * density;
        float sum = 0f;
        for (int i = 0; i < s.length(); i++) sum += ratio(s.charAt(i));
        float size = Math.min(maxSize, innerW / sum);
        float x = getWidth() - 18f * density;
        for (int i = s.length() - 1; i >= 0; i--) {
            char ch = s.charAt(i);
            float adv = ratio(ch) * size;
            G g = new G();
            g.ch = ch;
            g.size = size;
            g.cx = x - adv / 2f;
            g.baseline = baseline;
            g.birthIndex = birthMode == 1 ? i : -1;
            out.add(0, g);
            x -= adv;
        }
    }

    private ArrayList<G> buildLayout() {
        ArrayList<G> out = new ArrayList<>();
        float h = getHeight();
        layoutLine(small, h * 0.19f, h * 0.34f, 0, out);
        layoutLine(big, h * 0.46f, h * 0.86f, 1, out);
        return out;
    }

    // -------------------------------------------------------------- bitmaps for the kill scene
    private void buildBitmaps(G g) {
        float size = g.size;
        textP.setTextSize(size);
        float adv = Math.max(textP.measureText(String.valueOf(g.ch)), size * 0.3f);
        int pad = (int) Math.ceil(size * 0.32f);
        int bw = (int) Math.ceil(adv + 2 * pad);
        int bh = (int) Math.ceil(size * 1.45f + 2 * pad);
        float baselineB = pad + size * 0.95f;
        float centerX = bw / 2f;
        g.pivotX = centerX;
        g.pivotY = baselineB - size * 0.36f;

        Bitmap whole = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas wc = new Canvas(whole);
        GlyphStyle.ALIEN.draw(wc, textP, String.valueOf(g.ch), centerX, baselineB, size, 0.7f);

        // find the longest opaque stretch along the cut line -> where the bone goes
        float dx = (float) Math.cos(g.angle), dy = (float) Math.sin(g.angle);
        int R = Math.max(bw, bh);
        int bestStart = 0, bestLen = 0, curStart = 0, curLen = 0;
        for (int t = -R; t <= R; t++) {
            int px = Math.round(g.pivotX + dx * t), py = Math.round(g.pivotY + dy * t);
            boolean solid = px >= 0 && py >= 0 && px < bw && py < bh
                    && ((whole.getPixel(px, py) >>> 24) > 170);
            if (solid) {
                if (curLen == 0) curStart = t;
                curLen++;
                if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }
            } else curLen = 0;
        }
        float tc = bestLen > 0 ? bestStart + bestLen / 2f : 0f;
        float boneLen = Math.max(size * 0.14f, Math.min(bestLen * 0.8f, size * 0.5f));
        float boneX = g.pivotX + dx * tc, boneY = g.pivotY + dy * tc;

        // alien face: two eyes on the upper part, two stubby legs below the baseline
        p.setStyle(Paint.Style.FILL);
        float eyeY = baselineB - size * 0.58f;
        for (int i = 0; i < 2; i++) {
            float ex = centerX + (i == 0 ? -1 : 1) * adv * 0.2f;
            p.setColor(0xFFFFFFFF);
            wc.drawCircle(ex, eyeY, size * 0.085f, p);
            p.setColor(0xFF0B1B2E);
            wc.drawCircle(ex + size * 0.015f, eyeY + size * 0.01f, size * 0.045f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, size * 0.02f));
            p.setColor(0xFF0B4A22);
            wc.drawCircle(ex, eyeY, size * 0.085f, p);
            p.setStyle(Paint.Style.FILL);
        }
        for (int i = 0; i < 2; i++) {
            float lx = centerX + (i == 0 ? -1 : 1) * adv * 0.2f;
            rect.set(lx - size * 0.06f, baselineB - size * 0.02f, lx + size * 0.06f, baselineB + size * 0.2f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2FA84C);
            wc.drawRoundRect(rect, size * 0.06f, size * 0.06f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, size * 0.025f));
            p.setColor(0xFF0B4A22);
            wc.drawRoundRect(rect, size * 0.06f, size * 0.06f, p);
        }
        p.setStyle(Paint.Style.FILL);

        g.whole = whole;
        g.top = makeHalf(whole, g, bw, bh, true, boneX, boneY, boneLen);
        g.bottom = makeHalf(whole, g, bw, bh, false, boneX, boneY, boneLen);
    }

    private Bitmap makeHalf(Bitmap whole, G g, int bw, int bh, boolean upper,
                            float boneX, float boneY, float boneLen) {
        float size = g.size;
        float dx = (float) Math.cos(g.angle), dy = (float) Math.sin(g.angle);
        float nx = dy, ny = -dx;                 // normal pointing "up" from the cut line
        float s = upper ? 1f : -1f;
        float R = Math.max(bw, bh) * 2f;
        float px = g.pivotX, py = g.pivotY;

        Bitmap half = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas hc = new Canvas(half);

        Path poly = new Path();
        poly.moveTo(px - dx * R, py - dy * R);
        poly.lineTo(px + dx * R, py + dy * R);
        poly.lineTo(px + dx * R + s * nx * R, py + dy * R + s * ny * R);
        poly.lineTo(px - dx * R + s * nx * R, py - dy * R + s * ny * R);
        poly.close();

        hc.save();
        hc.clipPath(poly);
        hc.drawBitmap(whole, 0, 0, null);
        hc.restore();

        // red flesh on the cut face, only where the glyph has pixels (SRC_ATOP keeps the glyph shape)
        Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG);
        ap.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        float band = size * 0.11f;
        Path bp = new Path();
        bp.moveTo(px - dx * R, py - dy * R);
        bp.lineTo(px + dx * R, py + dy * R);
        bp.lineTo(px + dx * R + s * nx * band, py + dy * R + s * ny * band);
        bp.lineTo(px - dx * R + s * nx * band, py - dy * R + s * ny * band);
        bp.close();
        ap.setColor(0xFFE5202E);
        hc.drawPath(bp, ap);
        Path ep = new Path();
        ep.moveTo(px - dx * R, py - dy * R);
        ep.lineTo(px + dx * R, py + dy * R);
        ep.lineTo(px + dx * R + s * nx * band * 0.3f, py + dy * R + s * ny * band * 0.3f);
        ep.lineTo(px - dx * R + s * nx * band * 0.3f, py - dy * R + s * ny * band * 0.3f);
        ep.close();
        ap.setColor(0xFF9E0F1B);
        hc.drawPath(ep, ap);

        // small white bone sticking out of the cut (half of it lives on each piece)
        float th = Math.max(3f, size * 0.07f);
        hc.save();
        hc.clipPath(poly);
        hc.translate(boneX, boneY);
        hc.rotate((float) Math.toDegrees(g.angle));
        Paint bone = new Paint(Paint.ANTI_ALIAS_FLAG);
        bone.setStyle(Paint.Style.FILL);
        bone.setColor(0xFFFFFFF0);
        RectF br = new RectF(-boneLen / 2f, -th / 2f, boneLen / 2f, th / 2f);
        hc.drawRoundRect(br, th / 2f, th / 2f, bone);
        for (int e = -1; e <= 1; e += 2) {
            for (int k = -1; k <= 1; k += 2) {
                hc.drawCircle(e * boneLen / 2f, k * th * 0.62f, th * 0.72f, bone);
            }
        }
        Paint boneLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        boneLine.setStyle(Paint.Style.STROKE);
        boneLine.setStrokeWidth(Math.max(1f, th * 0.18f));
        boneLine.setColor(0xFFB9B9A0);
        hc.drawRoundRect(br, th / 2f, th / 2f, boneLine);
        hc.restore();
        return half;
    }

    private void releaseBitmaps() {
        for (G g : glyphs) {
            if (g.whole != null) g.whole.recycle();
            if (g.top != null) g.top.recycle();
            if (g.bottom != null) g.bottom.recycle();
            g.whole = g.top = g.bottom = null;
        }
        glyphs = new ArrayList<>();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releaseBitmaps();
    }

    // -------------------------------------------------------------- drawing
    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        drawScreen(c, w, h);

        long now = SystemClock.uptimeMillis();
        boolean needFrame = false;

        if (slicing) {
            float t = (now - sliceStartMs) / 1000f;
            long ns = System.nanoTime();
            float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (ns - lastNs) / 1e9f);
            lastNs = ns;
            drawSlice(c, t);
            ps.update(dt);
            ps.draw(c, p);
            if (t >= sliceTotal) {
                slicing = false;
                small = ""; big = ""; prevBig = "";
                releaseBitmaps();
                Runnable r = onSliceDone;
                onSliceDone = null;
                invalidate();
                if (r != null) r.run();
            } else {
                needFrame = true;
            }
        } else {
            for (G g : buildLayout()) {
                float sc = 1f, alpha = 1f;
                if (g.birthIndex >= 0) {
                    float age = (now - bigBirth[g.birthIndex]) / 1000f;
                    if (age < 0.3f) {
                        float k = age / 0.3f;
                        sc = easeOutBack(k);
                        alpha = Math.min(1f, k * 3f);
                        needFrame = true;
                    }
                }
                c.save();
                c.scale(sc, sc, g.cx, g.baseline - g.size * 0.35f);
                if (alpha < 1f) c.saveLayerAlpha(g.cx - g.size, g.baseline - g.size * 1.2f,
                        g.cx + g.size, g.baseline + g.size * 0.4f, (int) (alpha * 255));
                GlyphStyle.NEON.draw(c, textP, String.valueOf(g.ch), g.cx, g.baseline, g.size,
                        g.birthIndex >= 0 ? 1f : 0.6f);
                if (alpha < 1f) c.restore();
                c.restore();
            }
            ps.update(0.016f);
            if (ps.hasActive()) { ps.draw(c, p); needFrame = true; }
        }
        if (needFrame) postInvalidateOnAnimation();
    }

    private static float easeOutBack(float t) {
        float c1 = 2.2f, c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private void drawScreen(Canvas c, float w, float h) {
        float m = 2f * density;
        rect.set(m, m, w - m, h - m);
        float rad = 18f * density;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xDD02161A, 0xDD031F2B, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, rad, rad, p);
        p.setShader(null);

        // scan lines
        c.save();
        path.reset();
        path.addRoundRect(rect, rad, rad, Path.Direction.CW);
        c.clipPath(path);
        p.setColor(0xFF30FFD0);
        p.setAlpha(14);
        p.setStrokeWidth(1f);
        for (float y = m; y < h; y += 4f * density) c.drawLine(0, y, w, y, p);
        // soft top glass sheen
        p.setAlpha(255);
        p.setShader(new LinearGradient(0, m, 0, h * 0.4f, 0x2AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.4f, p);
        p.setShader(null);
        c.restore();

        // neon frame
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0xFF18F5C8);
        p.setStrokeWidth(5f * density);
        p.setAlpha(28);
        c.drawRoundRect(rect, rad, rad, p);
        p.setStrokeWidth(2f * density);
        p.setAlpha(220);
        c.drawRoundRect(rect, rad, rad, p);
        p.setAlpha(255);

        // status LED
        long ms = SystemClock.uptimeMillis();
        boolean on = (ms / 600) % 2 == 0;
        p.setStyle(Paint.Style.FILL);
        p.setColor(on ? 0xFF7BFF6B : 0xFF1E5A2A);
        c.drawCircle(18f * density, 16f * density, 3.5f * density, p);
    }

    private static float smooth(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3f - 2f * x);
    }

    private void drawSlice(Canvas c, float t) {
        for (int i = 0; i < glyphs.size(); i++) {
            G g = glyphs.get(i);
            if (g.whole == null) continue;
            float cutT = T_CUT + g.delay;
            float size = g.size;
            float pivotScreenY = g.baseline - size * 0.36f;
            // bitmap origin so that pivot lands on (g.cx, pivotScreenY) when no offsets are applied

            if (t < cutT) {
                // ---- alive: neon digit -> alien -> panic ----
                float a = smooth(t / T_ALIEN);
                float pop = 1f + 0.22f * (float) Math.sin(Math.PI * Math.min(1f, t / T_ALIEN));
                float ox = 0f, oy = 0f;
                if (t > T_PANIC) {
                    float k = Math.min(1f, (t - T_PANIC) / 0.2f);
                    ox = (float) Math.sin(t * 70f + g.jitter) * size * 0.04f * k;
                    oy = -Math.abs((float) Math.sin(t * 13f + g.jitter)) * size * 0.14f * k;
                }
                if (a < 1f) {
                    c.save();
                    c.scale(pop, pop, g.cx, g.baseline - size * 0.35f);
                    c.saveLayerAlpha(g.cx - size, g.baseline - size * 1.3f, g.cx + size, g.baseline + size * 0.5f,
                            (int) ((1f - a) * 255));
                    GlyphStyle.NEON.draw(c, textP, String.valueOf(g.ch), g.cx, g.baseline, size, 0.8f);
                    c.restore();
                    c.restore();
                }
                c.save();
                c.translate(ox, oy);
                c.scale(pop, pop, g.cx, g.baseline - size * 0.35f);
                bmpP.setAlpha((int) (a * 255));
                c.drawBitmap(g.whole, g.cx - g.pivotX, pivotScreenY - g.pivotY, bmpP);
                bmpP.setAlpha(255);
                c.restore();

                // alarm mark above the panicking alien
                if (t > T_PANIC) {
                    float blink = 0.6f + 0.4f * (float) Math.sin(t * 30f + g.jitter);
                    c.save();
                    c.translate(0, oy);
                    c.saveLayerAlpha(g.cx - size, g.baseline - size * 1.5f, g.cx + size, g.baseline - size * 0.5f,
                            (int) (blink * 255));
                    ALERT.draw(c, textP, "!", g.cx, g.baseline - size * 0.82f, size * 0.38f, 0.8f);
                    c.restore();
                    c.restore();
                }

                // the blade arrives just before the cut
                if (t > cutT - 0.08f) drawSlash(c, g, pivotScreenY, (t - (cutT - 0.08f)) / 0.08f, 1f);
            } else {
                float k = t - cutT;
                if (!g.fxDone) {
                    g.fxDone = true;
                    ps.sparks(g.cx, pivotScreenY, 14, 0xFFFFE066, size * 5f);
                    ps.sparks(g.cx, pivotScreenY, 6, 0xFFFF4D4D, size * 3.5f);
                    ps.smoke(g.cx, pivotScreenY, 4, size * 0.35f);
                }
                if (k < 0.14f) drawSlash(c, g, pivotScreenY, 1f, 1f - k / 0.14f);

                float fade = 1f - smooth((k - 0.35f) / 0.8f);
                if (fade <= 0f) continue;
                float grav = size * 1.7f;
                float open = smooth(k / 0.12f);
                float fall = 0.5f * grav * k * k;

                // top piece: kicked up/left, spins counter-clockwise
                drawHalf(c, g.top, g, pivotScreenY,
                        -size * 0.4f * smooth(k / 0.6f),
                        -size * 0.14f * open + fall,
                        -75f * k, fade);
                // bottom piece: drops right, spins clockwise
                drawHalf(c, g.bottom, g, pivotScreenY,
                        size * 0.25f * smooth(k / 0.6f),
                        size * 0.06f * open + fall * 1.15f,
                        60f * k, fade);

                // a puff of smoke trails each falling piece for a moment
                if (k < 0.5f && rnd.nextFloat() < 0.3f) {
                    ps.smoke(g.cx + (rnd.nextFloat() - 0.5f) * size * 0.5f,
                            pivotScreenY + fall, 1, size * 0.22f);
                }
            }
        }
    }

    private void drawHalf(Canvas c, Bitmap b, G g, float pivotScreenY, float dx, float dy, float rot, float alpha) {
        c.save();
        c.translate(g.cx + dx, pivotScreenY + dy);
        c.rotate(rot);
        bmpP.setAlpha((int) (alpha * 255));
        c.drawBitmap(b, -g.pivotX, -g.pivotY, bmpP);
        bmpP.setAlpha(255);
        c.restore();
    }

    /** Bright cutting line through the glyph. reveal 0..1 = how far the blade has travelled. */
    private void drawSlash(Canvas c, G g, float pivotScreenY, float reveal, float alpha) {
        float dx = (float) Math.cos(g.angle), dy = (float) Math.sin(g.angle);
        float half = g.size * 0.75f;
        float x0 = g.cx - dx * half, y0 = pivotScreenY - dy * half;
        float x1 = x0 + dx * half * 2f * Math.max(0f, Math.min(1f, reveal));
        float y1 = y0 + dy * half * 2f * Math.max(0f, Math.min(1f, reveal));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFF7DF9FF);
        p.setStrokeWidth(g.size * 0.16f);
        p.setAlpha((int) (70 * alpha));
        c.drawLine(x0, y0, x1, y1, p);
        p.setColor(0xFFFFFFFF);
        p.setStrokeWidth(g.size * 0.05f);
        p.setAlpha((int) (255 * alpha));
        c.drawLine(x0, y0, x1, y1, p);
        p.setAlpha(255);
        p.setStyle(Paint.Style.FILL);
    }
}
