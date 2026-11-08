package com.wspace.calculator;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** Translucent glass key with a coloured neon rim. Springs when pressed. */
final class GlassButton extends View {

    static final int DIGIT = 0, OPERATOR = 1, FUNCTION = 2, EQUALS = 3, ICON = 4;

    /** Special label keys that are drawn as icons instead of text. */
    static final String ICON_BACK = "⌫";
    static final String ICON_SOUND = "SND";

    private final String label;
    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint();
    private final RectF r = new RectF();
    private final Path path = new Path();
    private float press = 0f;       // 0 idle .. 1 fully pressed
    private ValueAnimator anim;
    private boolean muted = false;  // only for the sound icon
    private final int rim, fillTop, fillBottom;

    GlassButton(Context ctx, String label, int kind) {
        super(ctx);
        this.label = label;
        this.kind = kind;
        GlyphStyle.setup(textPaint);
        switch (kind) {
            case OPERATOR:
                rim = 0xFF3DE0FF; fillTop = 0x6639C8FF; fillBottom = 0x3318609A; break;
            case FUNCTION:
                rim = 0xFF8CFFB2; fillTop = 0x6642E08A; fillBottom = 0x33135A38; break;
            case EQUALS:
                rim = 0xFF7BFF6B; fillTop = 0xCC52F07A; fillBottom = 0xAA14A04A; break;
            case ICON:
                rim = 0xFF3DF2D0; fillTop = 0x6630E0C0; fillBottom = 0x33104A50; break;
            default:
                rim = 0xFF2EF2C2; fillTop = 0x5530D8C8; fillBottom = 0x2A0F5C5A; break;
        }
        setClickable(true);
    }

    void setMuted(boolean m) { muted = m; invalidate(); }

    private void animateTo(float target) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(press, target);
        anim.setDuration(target > press ? 70 : 260);
        if (target < press) anim.setInterpolator(new OvershootInterpolator(2.5f));
        anim.addUpdateListener(a -> { press = (Float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animateTo(1f);
                return true;
            case MotionEvent.ACTION_UP:
                animateTo(0f);
                if (e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight()) {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                animateTo(0f);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float pad = Math.min(w, h) * 0.07f;
        float s = 1f - 0.08f * press;
        c.save();
        c.scale(s, s, w / 2f, h / 2f);

        r.set(pad, pad, w - pad, h - pad);
        float rad = Math.min(r.width(), r.height()) * 0.3f;

        // outer neon glow (stacked strokes)
        p.setStyle(Paint.Style.STROKE);
        p.setColor(rim);
        float g = 1f + 0.8f * press;
        p.setStrokeWidth(pad * 1.6f);
        p.setAlpha((int) Math.min(255, 30 * g));
        c.drawRoundRect(r, rad, rad, p);
        p.setStrokeWidth(pad * 0.95f);
        p.setAlpha((int) Math.min(255, 60 * g));
        c.drawRoundRect(r, rad, rad, p);

        // glass body
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, fillTop, fillBottom, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        if (press > 0f) {
            p.setColor(rim);
            p.setAlpha((int) (80 * press));
            c.drawRoundRect(r, rad, rad, p);
        }

        // specular highlight on the top half
        RectF hi = new RectF(r.left + r.width() * 0.08f, r.top + r.height() * 0.06f,
                r.right - r.width() * 0.08f, r.top + r.height() * 0.46f);
        p.setShader(new LinearGradient(0, hi.top, 0, hi.bottom, 0x66FFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(hi, rad * 0.8f, rad * 0.8f, p);
        p.setShader(null);

        // crisp rim
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(pad * 0.35f);
        p.setColor(rim);
        p.setAlpha(230);
        c.drawRoundRect(r, rad, rad, p);
        p.setAlpha(255);

        float cx = w / 2f, cy = h / 2f;
        if (ICON_BACK.equals(label)) {
            drawBackIcon(c, cx, cy, Math.min(w, h) * 0.2f);
        } else if (ICON_SOUND.equals(label)) {
            drawSoundIcon(c, cx, cy, Math.min(w, h) * 0.28f);
        } else {
            float size = Math.min(w, h) * (label.length() > 1 ? 0.34f : 0.5f);
            GlyphStyle style = kind == EQUALS ? GlyphStyle.WHITE : (kind == DIGIT ? GlyphStyle.NEON : GlyphStyle.WHITE);
            style.draw(c, textPaint, label, cx, cy + size * 0.36f, size, 0.9f + 0.6f * press);
        }
        c.restore();
    }

    private void drawBackIcon(Canvas c, float cx, float cy, float u) {
        path.reset();
        path.moveTo(cx - u * 1.7f, cy);
        path.lineTo(cx - u * 0.6f, cy - u * 1.0f);
        path.lineTo(cx + u * 1.6f, cy - u * 1.0f);
        path.lineTo(cx + u * 1.6f, cy + u * 1.0f);
        path.lineTo(cx - u * 0.6f, cy + u * 1.0f);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x4430FF9A);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(u * 0.28f);
        p.setColor(0xFFE9FFF4);
        c.drawPath(path, p);
        p.setStrokeWidth(u * 0.26f);
        c.drawLine(cx - u * 0.1f, cy - u * 0.4f, cx + u * 0.9f, cy + u * 0.4f, p);
        c.drawLine(cx - u * 0.1f, cy + u * 0.4f, cx + u * 0.9f, cy - u * 0.4f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawSoundIcon(Canvas c, float cx, float cy, float u) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFE9FFF4);
        path.reset();
        path.moveTo(cx - u * 1.0f, cy - u * 0.4f);
        path.lineTo(cx - u * 0.4f, cy - u * 0.4f);
        path.lineTo(cx + u * 0.3f, cy - u * 1.0f);
        path.lineTo(cx + u * 0.3f, cy + u * 1.0f);
        path.lineTo(cx - u * 0.4f, cy + u * 0.4f);
        path.lineTo(cx - u * 1.0f, cy + u * 0.4f);
        path.close();
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(u * 0.2f);
        if (muted) {
            p.setColor(0xFFFF5C7A);
            c.drawLine(cx + u * 0.7f, cy - u * 0.6f, cx + u * 1.5f, cy + u * 0.6f, p);
            c.drawLine(cx + u * 0.7f, cy + u * 0.6f, cx + u * 1.5f, cy - u * 0.6f, p);
        } else {
            p.setColor(0xFF7DF9FF);
            r.set(cx - u * 0.1f, cy - u * 0.7f, cx + u * 1.1f, cy + u * 0.7f);
            c.drawArc(r, -45f, 90f, false, p);
            r.set(cx - u * 0.4f, cy - u * 1.1f, cx + u * 1.6f, cy + u * 1.1f);
            c.drawArc(r, -45f, 90f, false, p);
        }
        p.setStyle(Paint.Style.FILL);
    }
}
