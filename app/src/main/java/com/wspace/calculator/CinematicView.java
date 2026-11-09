package com.wspace.calculator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * Full-screen cinematic shown when an equation with three or more operations is completed.
 * Everything is driven by the real audio playheads, so pictures, typing and sound stay locked:
 *
 *  1. FLOAT   - the small green character floats on black among stars while T R R R plays and the
 *               sentence is typed letter by letter, only while a burst is audible.
 *  2. RESULT  - the real result of the equation in big glowing digits.
 *  3. KILL    - red alarm, a slash charges; KILL starts and the big character picture splits in two
 *               exactly on the hit: white flash, red light band, flying sparks.
 *
 * Tapping the screen skips the scene, so it can never trap the user.
 */
final class CinematicView extends View {

    private static final int PH_FLOAT = 0, PH_RESULT = 1, PH_KILL = 2;

    private static final int MINT = 0xFF8DFFB0, MINT_SHADOW = 0xFF06301C, MINT_DIM = 0xFF4FB88C;

    private static final float HOLD_AFTER_TEXT = 0.45f;   // pause after the last letter
    private static final float RESULT_TIME = 1.5f;        // how long the result is admired
    private static final float KILL_PRE = 0.6f;           // charging time before the KILL sound starts
    private static final float KILL_HOLD = 0.65f;         // after the KILL sound has finished
    private static final float MAX_TOTAL = 14f;           // watchdog: the scene can never run longer

    // geometry of the sprites inside their source pictures (written by tools/make_assets.py)
    private static final float CHAR_X = 265f, CHAR_Y = 335f;       // float_char.png in the 940x879 picture
    private static final float MARKS_X = 677f, MARKS_Y = 371f;     // float_marks.png in the same picture
    private static final float TOP_X = 101f, TOP_Y = 241f;         // cut_top.png in the 941x1672 picture
    private static final float BOT_X = 364f, BOT_Y = 741f;         // cut_bottom.png in the same picture
    private static final float CUT_CX = 461.5f, CUT_CY = 693.5f;   // centre of both halves together
    private static final float CUT_W = 721f, CUT_H = 905f;         // their combined size
    private static final float SLASH_ANGLE = -0.70f;               // direction of the cut (radians)

    private final SoundEngine sound;
    private final Paint p = new Paint();
    private final Paint bmpP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glowP = new Paint();
    private final Random rnd = new Random(7);
    private final float density;

    private Bitmap floatChar, floatMarks, cutTop, cutBottom;

    // stars
    private static final int STARS = 70;
    private final float[] stX = new float[STARS], stY = new float[STARS], stS = new float[STARS],
            stP = new float[STARS], stV = new float[STARS];
    // red light streaks along the cut
    private static final int STREAKS = 16;
    private final float[] skOff = new float[STREAKS], skLen = new float[STREAKS], skSpd = new float[STREAKS],
            skPos = new float[STREAKS], skTh = new float[STREAKS];
    private Shader charGlow, resultGlow;

    // scene state
    private boolean running = false;
    private int phase = PH_FLOAT;
    private long startMs;
    private long lastNs;
    private float p2Start, p3Start;
    private ScenePlan.Clock trrrClock, killClock;
    private float t1Last;
    private boolean killStarted, impacted;
    private float killWall, tk;
    private String exprText = "", resultText = "";
    private Runnable onDone;
    private ParticleSystem ps = new ParticleSystem(420);

    // layout of the cut pieces (screen space)
    private float ks, topFx, topFy, botFx, botFy, midX, midY;

    CinematicView(Context ctx, SoundEngine sound) {
        super(ctx);
        this.sound = sound;
        density = ctx.getResources().getDisplayMetrics().density;
        setClickable(true);                // swallow touches while the scene plays
        setVisibility(GONE);
    }

    // ------------------------------------------------------------------ assets
    /** Decodes the pictures off the UI thread so the scene starts without a hitch. */
    void preload() {
        Thread t = new Thread(this::loadBitmaps, "wspace-scene-assets");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private synchronized void loadBitmaps() {
        if (floatChar != null && cutTop != null) return;
        try {
            floatChar = decode(R.drawable.float_char);
            floatMarks = decode(R.drawable.float_marks);
            cutTop = decode(R.drawable.cut_top);
            cutBottom = decode(R.drawable.cut_bottom);
        } catch (Throwable ignored) {
            // pictures are optional: the scene still plays its sound, text and effects
        }
    }

    private Bitmap decode(int res) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeResource(getResources(), res, o);
    }

    // ------------------------------------------------------------------ control
    /**
     * @param expr   the equation as shown on the calculator (display glyphs)
     * @param result the real, already computed result (display glyphs)
     * @param done   called once when the scene is over (or skipped)
     */
    void start(String expr, String result, Runnable done) {
        loadBitmaps();
        exprText = expr;
        resultText = result;
        onDone = done;
        ps = new ParticleSystem(420);
        phase = PH_FLOAT;
        killStarted = false;
        impacted = false;
        tk = 0f;
        t1Last = 0f;
        trrrClock = new ScenePlan.Clock(ScenePlan.TRRR_LEN);
        killClock = null;
        startMs = SystemClock.uptimeMillis();
        lastNs = 0;
        running = true;

        animate().cancel();
        setAlpha(0f);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(220).start();
        sound.playScene("trrr");
        invalidate();
    }

    private void finish() {
        if (!running) return;
        running = false;
        sound.stop("trrr");
        sound.stop("kill");
        Runnable r = onDone;
        onDone = null;
        if (r != null) r.run();
    }

    /** Ends the scene right away (back key / app paused). */
    void abort() { finish(); }

    boolean isRunning() { return running; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP && running
                && SystemClock.uptimeMillis() - startMs > 400) {
            finish();                       // tap = skip
        }
        return true;
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    // ------------------------------------------------------------------ geometry
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        Random r = new Random(21);
        for (int i = 0; i < STARS; i++) {
            stX[i] = r.nextFloat();
            stY[i] = r.nextFloat();
            stS[i] = r.nextFloat() < 0.15f ? 2f : 1f;
            stP[i] = r.nextFloat() * 6.28f;
            stV[i] = 0.01f + r.nextFloat() * 0.03f;
        }
        for (int i = 0; i < STREAKS; i++) {
            skOff[i] = (r.nextFloat() * 2f - 1f);
            skLen[i] = 0.12f + r.nextFloat() * 0.35f;
            skSpd[i] = 0.5f + r.nextFloat() * 1.3f;
            skPos[i] = r.nextFloat();
            skTh[i] = 0.02f + r.nextFloat() * 0.07f;
        }
        charGlow = new RadialGradient(w / 2f, h * 0.34f, Math.max(1f, w * 0.75f),
                new int[]{0x3A5CFFB0, 0x00000000}, null, Shader.TileMode.CLAMP);
        resultGlow = new RadialGradient(w / 2f, h * 0.42f, Math.max(1f, w * 0.8f),
                new int[]{0x4A5CFFB0, 0x00000000}, null, Shader.TileMode.CLAMP);
    }

    private void layoutCut(float w, float h) {
        if (cutTop == null || cutBottom == null) return;
        ks = Math.min(0.95f * w / CUT_W, 0.60f * h / CUT_H);
        float ox = w / 2f - CUT_CX * ks, oy = h * 0.52f - CUT_CY * ks;
        topFx = ox + (TOP_X + cutTop.getWidth() / 2f) * ks;
        topFy = oy + (TOP_Y + cutTop.getHeight() / 2f) * ks;
        botFx = ox + (BOT_X + cutBottom.getWidth() / 2f) * ks;
        botFy = oy + (BOT_Y + cutBottom.getHeight() / 2f) * ks;
        midX = (topFx + botFx) / 2f;
        midY = (topFy + botFy) / 2f;
    }

    // ------------------------------------------------------------------ helpers
    private static float clamp01(float x) { return Math.max(0f, Math.min(1f, x)); }

    private static float smooth(float x) {
        x = clamp01(x);
        return x * x * (3f - 2f * x);
    }

    private static float easeOutCubic(float x) {
        x = 1f - clamp01(x);
        return 1f - x * x * x;
    }

    private static float easeOutBack(float x) {
        x = clamp01(x);
        float c1 = 1.70158f, c3 = c1 + 1f, u = x - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private static int alpha(int color, float a) {
        int al = Math.max(0, Math.min(255, Math.round(a * 255f)));
        return (al << 24) | (color & 0x00FFFFFF);
    }

    /** Glowing pixel text: soft halo rings, hard shadow, then the bright core. */
    private void glowText(Canvas c, String s, float x, float y, float u, int core, int halo, float a, boolean strong) {
        if (s.isEmpty() || a <= 0f) return;
        float o1 = Math.max(1f, u * 0.55f), o2 = Math.max(2f, u * 1.2f);
        if (strong) {
            int ha = alpha(halo, 0.16f * a);
            float[][] ring = {{o2, 0}, {-o2, 0}, {0, o2}, {0, -o2}, {o2, o2}, {-o2, -o2}, {o2, -o2}, {-o2, o2}};
            for (float[] d : ring) PixelFont.text(c, p, s, x + d[0], y + d[1], u, ha);
        }
        int ha2 = alpha(halo, (strong ? 0.30f : 0.20f) * a);
        float[][] ring2 = {{o1, 0}, {-o1, 0}, {0, o1}, {0, -o1}};
        for (float[] d : ring2) PixelFont.text(c, p, s, x + d[0], y + d[1], u, ha2);
        PixelFont.text(c, p, s, x + Math.max(1f, u * 0.5f), y + Math.max(1f, u * 0.5f), u, alpha(0xFF000000, 0.55f * a));
        PixelFont.text(c, p, s, x, y, u, alpha(core, a));
    }

    private void drawStars(Canvas c, float w, float h, float wall, float a) {
        p.setStyle(Paint.Style.FILL);
        float u = Math.max(2f, Math.round(density * 1.4f));
        for (int i = 0; i < STARS; i++) {
            float y = ((stY[i] + wall * stV[i]) % 1f) * h;
            float x = stX[i] * w;
            float tw = 0.5f + 0.5f * (float) Math.sin(wall * (0.8f + stV[i] * 40f) + stP[i]);
            p.setColor(alpha(i % 5 == 0 ? 0xFF9BE8DE : 0xFFFFFFFF, a * (0.25f + 0.75f * tw)));
            float sz = u * stS[i];
            c.drawRect(Math.round(x), Math.round(y), Math.round(x) + sz, Math.round(y) + sz, p);
            if (stS[i] > 1f && tw > 0.7f) {
                p.setColor(alpha(0xFFFFFFFF, a * 0.35f));
                c.drawRect(x - sz, y, x, y + sz, p);
                c.drawRect(x + sz, y, x + 2 * sz, y + sz, p);
                c.drawRect(x, y - sz, x + sz, y, p);
                c.drawRect(x, y + sz, x + sz, y + 2 * sz, p);
            }
        }
    }

    private void drawRedFrame(Canvas c, float w, float h, float strength) {
        if (strength <= 0f) return;
        p.setStyle(Paint.Style.FILL);
        float fu = Math.max(6f, Math.round(w * 0.018f));
        for (int i = 0; i < 4; i++) {
            p.setColor(alpha(0xFFFF1A30, strength * (0.85f - i * 0.2f)));
            float o = i * fu;
            c.drawRect(o, o, w - o, o + fu, p);
            c.drawRect(o, h - o - fu, w - o, h - o, p);
            c.drawRect(o, o + fu, o + fu, h - o - fu, p);
            c.drawRect(w - o - fu, o + fu, w - o, h - o - fu, p);
        }
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w == 0f || !running) {
            c.drawColor(0xFF000000);
            return;
        }
        long nowMs = SystemClock.uptimeMillis();
        long ns = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        float wall = (nowMs - startMs) / 1000f;
        layoutCut(w, h);

        // ---- clocks and phase changes (all audio-driven) ----
        if (phase == PH_FLOAT) {
            t1Last = trrrClock.update(wall, sound.playheadSec("trrr"), dt);
            if (t1Last >= ScenePlan.TRRR_LEN + HOLD_AFTER_TEXT) {
                phase = PH_RESULT;
                p2Start = wall;
                ps.sparks(w / 2f, h * 0.42f, 26, 0xFF8DFFB0, w * 0.5f);
                ps.sparks(w / 2f, h * 0.42f, 14, 0xFFFFFFFF, w * 0.6f);
            }
        }
        if (phase == PH_RESULT && wall - p2Start >= RESULT_TIME) {
            phase = PH_KILL;
            p3Start = wall;
        }
        float e2 = wall - p2Start;
        float e3 = wall - p3Start;
        if (phase == PH_KILL) {
            if (!killStarted && e3 >= KILL_PRE) {
                killStarted = true;
                killWall = wall;
                killClock = new ScenePlan.Clock(ScenePlan.KILL_LEN);
                sound.playScene("kill");
            }
            if (killStarted) tk = killClock.update(wall - killWall, sound.playheadSec("kill"), dt);
        }
        float k = impacted || tk >= ScenePlan.KILL_IMPACT ? tk - ScenePlan.KILL_IMPACT : -1f;   // time since the hit

        // ---- shake after the hit ----
        float shake = k >= 0f ? w * 0.028f * Math.max(0f, 1f - k / 0.6f) : 0f;
        c.save();
        if (shake > 0f) {
            c.translate(Math.round((float) Math.sin(wall * 95f) * shake), Math.round((float) Math.cos(wall * 83f) * shake));
        }

        // ---- backdrop ----
        c.drawColor(0xFF000000);
        if (phase != PH_KILL) {
            glowP.setShader(phase == PH_FLOAT ? charGlow : resultGlow);
            c.drawRect(0, 0, w, h, glowP);
            glowP.setShader(null);
        }
        drawStars(c, w, h, wall, 1f);

        if (phase == PH_FLOAT) {
            drawFloat(c, w, h, wall, t1Last, 1f);
        } else if (phase == PH_RESULT) {
            float fade = 1f - smooth(e2 / 0.35f);
            if (fade > 0f) drawFloat(c, w, h, wall, ScenePlan.TRRR_LEN, fade);
            drawResult(c, w, h, e2);
        } else {
            drawKill(c, w, h, wall, e3, k, dt);
        }
        ps.update(dt);
        ps.draw(c, p);
        c.restore();

        // white impact flash (not shaken)
        if (k >= 0f && k < 0.2f) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(alpha(0xFFFFFFFF, 0.9f * (1f - k / 0.2f)));
            c.drawRect(0, 0, w, h, p);
        }

        // small hint so the scene never feels like a trap
        if (wall > 1.0f && phase != PH_KILL) {
            String hint = "TAP TO SKIP";
            float hu = Math.max(2f, Math.round(density * 1.6f));
            float a = 0.25f + 0.2f * (float) Math.sin(wall * 4f);
            PixelFont.text(c, p, hint, Math.round((w - PixelFont.width(hint, hu)) / 2f), Math.round(h * 0.94f), hu,
                    alpha(0xFF9FFFE8, a));
        }

        if (phase == PH_KILL && killStarted && tk >= ScenePlan.KILL_LEN + KILL_HOLD || wall > MAX_TOTAL) {
            finish();
        } else if (running) {
            postInvalidateOnAnimation();
        }
    }

    // ---- phase 1 ------------------------------------------------------------
    private void drawFloat(Canvas c, float w, float h, float wall, float t, float fade) {
        // character: slow sine bob + a tiny tilt, with a pixel shadow that breathes with it
        float bob = (float) Math.sin(wall * 2f * Math.PI * 0.85) * h * 0.012f;
        float tilt = (float) Math.sin(wall * 2f * Math.PI * 0.42) * 2.2f;
        float cx = w / 2f, cy = h * 0.34f + bob - (1f - fade) * h * 0.04f;
        if (floatChar != null) {
            float cs = Math.min(w * 0.55f / floatChar.getWidth(), h * 0.30f / floatChar.getHeight());
            float halfW = floatChar.getWidth() * cs / 2f, halfH = floatChar.getHeight() * cs / 2f;

            // shadow
            float sk = 1f - 0.12f * (bob / (h * 0.012f));
            float sw = halfW * 1.15f * sk, sy = cy - bob + halfH + h * 0.022f;
            float su = Math.max(3f, Math.round(density * 3f));
            p.setStyle(Paint.Style.FILL);
            for (int i = -2; i <= 2; i++) {
                float rw = sw * (float) Math.sqrt(1f - (i * i) / 9f);
                p.setColor(alpha(0xFF4A4468, 0.55f * fade));
                c.drawRect(cx - rw, sy + i * su, cx + rw, sy + (i + 1) * su, p);
            }

            c.save();
            c.translate(cx, cy);
            c.rotate(tilt);
            c.scale(cs * (1f + 0.1f * (1f - fade)), cs * (1f + 0.1f * (1f - fade)));
            bmpP.setAlpha((int) (255 * fade));
            c.drawBitmap(floatChar, -floatChar.getWidth() / 2f, -floatChar.getHeight() / 2f, bmpP);
            if (floatMarks != null) {                       // the excited "!!" marks blink beside the head
                float mx = (MARKS_X + floatMarks.getWidth() / 2f) - (CHAR_X + floatChar.getWidth() / 2f);
                float my = (MARKS_Y + floatMarks.getHeight() / 2f) - (CHAR_Y + floatChar.getHeight() / 2f);
                float pulse = 0.55f + 0.45f * (float) Math.sin(wall * 11f);
                bmpP.setAlpha((int) (255 * fade * pulse));
                c.drawBitmap(floatMarks, mx - floatMarks.getWidth() / 2f, my - floatMarks.getHeight() / 2f, bmpP);
            }
            bmpP.setAlpha(255);
            c.restore();
        }

        // typewriter text, synced to the audible bursts of T R R R
        int[] shown = ScenePlan.perLine(ScenePlan.revealedChars(t));
        String[] lines = ScenePlan.PHRASE_LINES;
        int widest = 1;
        for (String s : lines) widest = Math.max(widest, FontData.textWidth(s));
        float u = Math.max(3f, (float) Math.floor(w * 0.88f / widest));
        float lineH = FontData.HEIGHT * u + 3f * u;
        float y0 = h * 0.60f;
        int lastLine = -1;
        for (int i = 0; i < lines.length; i++) if (shown[i] > 0) lastLine = i;
        for (int i = 0; i < lines.length; i++) {
            if (shown[i] <= 0) continue;
            float fullW = FontData.textWidth(lines[i]) * u;
            float x = Math.round((w - fullW) / 2f), y = Math.round(y0 + i * lineH);
            String part = lines[i].substring(0, shown[i]);
            glowText(c, part, x, y, u, 0xFFEFFFF6, MINT, fade, false);
            if (i == lastLine && fade >= 1f && t < ScenePlan.TRRR_LEN + 0.3f) {     // typing cursor
                float cxw = x + FontData.textWidth(part) * u + u;
                if (((int) (wall * 8f)) % 2 == 0) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(alpha(MINT, 0.9f));
                    c.drawRect(cxw, y, cxw + u * 3f, y + FontData.HEIGHT * u, p);
                }
            }
        }
    }

    // ---- phase 2 ------------------------------------------------------------
    private float resultUnit(float w, float h) {
        int units = Math.max(1, FontData.textWidth(resultText));
        float u = (float) Math.floor(Math.min(w * 0.88f / units, h * 0.10f / FontData.HEIGHT));
        return Math.max(2f, u);
    }

    /** Result digits with a staggered pop-in (age = seconds since the result began to appear). */
    private void drawResultDigits(Canvas c, float w, float cy, float u, float a, float age) {
        String s = resultText;
        float tw = FontData.textWidth(s) * u;
        float x = Math.round((w - tw) / 2f), y = Math.round(cy - FontData.HEIGHT * u / 2f);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            float birth = 0.12f + i * 0.07f;
            float kk = clamp01((age - birth) / 0.28f);
            if (kk > 0f) {
                float sc = Math.max(0.01f, easeOutBack(kk));
                float gx = x, gw = FontData.width(ch) * u;
                c.save();
                c.scale(sc, sc, gx + gw / 2f, y + FontData.HEIGHT * u / 2f);
                glowText(c, String.valueOf(ch), gx, y, u, 0xFFF2FFF7, MINT, a * Math.min(1f, kk * 3f), true);
                c.restore();
            }
            x += (FontData.width(ch) + 1) * u;
        }
    }

    private void drawResult(Canvas c, float w, float h, float e2) {
        float u = resultUnit(w, h);
        float cy = h * 0.42f;
        // the equation, small and dim above the result
        int eu = Math.max(1, FontData.textWidth(exprText));
        float ue = Math.max(2f, (float) Math.floor(Math.min(w * 0.82f / eu, u * 0.38f)));
        float ea = smooth((e2 - 0.05f) / 0.3f);
        PixelFont.shadowed(c, p, exprText, Math.round((w - eu * ue) / 2f), Math.round(cy - FontData.HEIGHT * u / 2f - ue * 4f - FontData.HEIGHT * ue),
                ue, alpha(MINT_DIM, ea), alpha(0xFF06241A, ea));
        drawResultDigits(c, w, cy, u, 1f, e2);
    }

    // ---- phase 3 ------------------------------------------------------------
    private void drawKill(Canvas c, float w, float h, float wall, float e3, float k, float dt) {
        float pre = clamp01(e3 / KILL_PRE);
        float pulse = 0.5f + 0.5f * (float) Math.sin(wall * 14f);

        // red alarm frame: builds up, flares on the hit, then settles
        float frame = k < 0f ? (0.15f + 0.55f * smooth(pre)) * (0.6f + 0.4f * pulse)
                : (0.35f + 0.45f * Math.max(0f, 1f - k / 1.4f)) * (0.8f + 0.2f * pulse);
        drawRedFrame(c, w, h, frame);

        float diag = (float) Math.hypot(w, h);
        float deg = (float) Math.toDegrees(SLASH_ANGLE);

        if (k < 0f) {
            // charging: a thin blade of light grows through the middle of the screen
            float reveal = easeOutCubic(pre);
            c.save();
            c.translate(midX, midY);
            c.rotate(deg);
            float len = diag * reveal;
            p.setStyle(Paint.Style.FILL);
            p.setColor(alpha(0xFFFF2A3A, 0.25f));
            c.drawRect(-len / 2f, -ks * 14f, len / 2f, ks * 14f, p);
            p.setColor(alpha(0xFFFFFFFF, 0.95f));
            c.drawRect(-len / 2f, -Math.max(2f, ks * 2.5f), len / 2f, Math.max(2f, ks * 2.5f), p);
            c.restore();
            // a charging spark at the centre
            float sparkS = Math.max(4f, ks * (6f + 10f * pre)) * (0.7f + 0.3f * pulse);
            p.setColor(alpha(0xFFFFFFFF, 0.9f));
            c.drawRect(midX - sparkS, midY - sparkS, midX + sparkS, midY + sparkS, p);
        } else {
            if (!impacted) {
                impacted = true;
                ps.sparks(midX, midY, 60, 0xFFFFE066, w * 1.1f);
                ps.sparks(midX, midY, 44, 0xFFFF3B3B, w * 0.9f);
                ps.sparks(midX, midY, 30, 0xFFFFFFFF, w * 1.3f);
                ps.smoke(midX, midY, 16, ks * 70f);
            }
            drawLightBand(c, w, h, k, diag, deg);
            if (k < 0.6f) {                                         // square shockwave
                float rk = k / 0.6f, rad = ks * (60f + 520f * rk);
                float th = Math.max(4f, ks * 14f * (1f - rk));
                p.setStyle(Paint.Style.FILL);
                p.setColor(alpha(0xFFFFFFFF, 1f - rk));
                c.drawRect(midX - rad, midY - rad, midX + rad, midY - rad + th, p);
                c.drawRect(midX - rad, midY + rad - th, midX + rad, midY + rad, p);
                c.drawRect(midX - rad, midY - rad + th, midX - rad + th, midY + rad - th, p);
                c.drawRect(midX + rad - th, midY - rad + th, midX + rad, midY + rad - th, p);
            }
            // rising embers along the band while it burns
            if (k < 1.5f && rnd.nextFloat() < 0.55f) {
                float along = (rnd.nextFloat() - 0.5f) * diag * 0.8f;
                float ex = midX + (float) Math.cos(SLASH_ANGLE) * along, ey = midY + (float) Math.sin(SLASH_ANGLE) * along;
                ps.spawn(ParticleSystem.STAR, ex, ey, (rnd.nextFloat() - 0.5f) * 50f, -(40f + rnd.nextFloat() * 110f),
                        0.8f + rnd.nextFloat() * 0.8f, 3f + rnd.nextFloat() * 3f,
                        rnd.nextBoolean() ? 0xFFFF4A3A : 0xFFFFB347);
            }
            drawHalves(c, w, h, wall, k);
        }

        // the result moves up to the top and keeps shining there
        float u = resultUnit(w, h);
        float mv = smooth(e3 / 0.5f);
        float uTop = Math.max(2f, (float) Math.floor(u * 0.55f));
        float uNow = u + (uTop - u) * mv;
        float cy = h * 0.42f + (h * 0.115f - h * 0.42f) * mv;
        drawResultDigits(c, w, cy, uNow, 1f - 0.25f * mv, 99f);
    }

    /** The red light that bursts out along the cut: layered bands, hot core and speed streaks. */
    private void drawLightBand(Canvas c, float w, float h, float k, float diag, float deg) {
        float grow = easeOutCubic(k / 0.22f);
        float a = (0.55f + 0.45f * Math.max(0f, 1f - k / 1.3f)) * (0.92f + 0.08f * (float) Math.sin(k * 18f));
        float thick = ks * 120f * (0.25f + 0.75f * grow);
        float len = diag * 1.3f * grow;
        c.save();
        c.translate(midX, midY);
        c.rotate(deg);
        p.setStyle(Paint.Style.FILL);
        p.setColor(alpha(0xFF8A0010, 0.30f * a));
        c.drawRect(-len / 2f, -thick * 1.7f, len / 2f, thick * 1.7f, p);
        p.setColor(alpha(0xFFFF1A30, 0.50f * a));
        c.drawRect(-len / 2f, -thick * 1.0f, len / 2f, thick * 1.0f, p);
        p.setColor(alpha(0xFFFF6A72, 0.70f * a));
        c.drawRect(-len / 2f, -thick * 0.5f, len / 2f, thick * 0.5f, p);
        p.setColor(alpha(0xFFFFFFFF, 0.92f * a));
        c.drawRect(-len / 2f, -thick * 0.17f, len / 2f, thick * 0.17f, p);
        // streaks racing along the band
        for (int i = 0; i < STREAKS; i++) {
            float pos = ((skPos[i] + k * skSpd[i] * 0.9f) % 1f) * diag * 1.3f - diag * 0.65f;
            float sl = diag * skLen[i] * grow;
            float oy = skOff[i] * thick * 1.5f, th = Math.max(2f, thick * skTh[i]);
            p.setColor(alpha(i % 3 == 0 ? 0xFFFFFFFF : 0xFFFF5060, 0.75f * a));
            c.drawRect(pos, oy - th, pos + sl, oy + th, p);
        }
        c.restore();
    }

    /** The two halves of the big picture burst out of the cut and drift apart. */
    private void drawHalves(Canvas c, float w, float h, float wall, float k) {
        if (cutTop == null || cutBottom == null) return;
        float fk = clamp01(k / 0.55f);
        float ef = easeOutBack(fk);
        float sc = ks * (0.55f + 0.45f * easeOutCubic(fk));
        float settle = 1f - easeOutCubic(fk);
        // after landing they keep floating apart very slowly
        float sep = w * 0.03f * smooth(k / 2.2f);
        float bobT = (float) Math.sin(wall * 2.2f) * h * 0.006f;
        float bobB = (float) Math.sin(wall * 2.0f + 1.7f) * h * 0.006f;
        float dx = botFx - topFx, dy = botFy - topFy;
        float dl = Math.max(1f, (float) Math.hypot(dx, dy));
        float nx = dx / dl, ny = dy / dl;

        drawPiece(c, cutTop, midX + (topFx - midX) * ef - nx * sep, midY + (topFy - midY) * ef - ny * sep + bobT,
                sc, 38f * settle);
        drawPiece(c, cutBottom, midX + (botFx - midX) * ef + nx * sep, midY + (botFy - midY) * ef + ny * sep + bobB,
                sc, -34f * settle);
    }

    private void drawPiece(Canvas c, Bitmap b, float cx, float cy, float scale, float rotDeg) {
        c.save();
        c.translate(cx, cy);
        c.rotate(rotDeg);
        c.scale(scale, scale);
        c.drawBitmap(b, -b.getWidth() / 2f, -b.getHeight() / 2f, bmpP);
        c.restore();
    }
}
