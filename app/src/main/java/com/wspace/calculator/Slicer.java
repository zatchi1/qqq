package com.wspace.calculator;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;

/**
 * Real slicing: splits a bitmap along a line into an upper and a lower bitmap, paints red flesh on
 * each cut face (only where the picture has pixels) and a small white bone across the cut.
 */
final class Slicer {
    private Slicer() {}

    /** Where along the cut line the bone should sit: the middle of the longest opaque stretch. */
    static float[] boneSpot(Bitmap whole, float angle, float pivotX, float pivotY, float maxLen) {
        int bw = whole.getWidth(), bh = whole.getHeight();
        float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
        int R = Math.max(bw, bh);
        int bestStart = 0, bestLen = 0, curStart = 0, curLen = 0;
        for (int t = -R; t <= R; t++) {
            int px = Math.round(pivotX + dx * t), py = Math.round(pivotY + dy * t);
            boolean solid = px >= 0 && py >= 0 && px < bw && py < bh && ((whole.getPixel(px, py) >>> 24) > 170);
            if (solid) {
                if (curLen == 0) curStart = t;
                curLen++;
                if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }
            } else {
                curLen = 0;
            }
        }
        float tc = bestLen > 0 ? bestStart + bestLen / 2f : 0f;
        float len = Math.min(bestLen * 0.8f, maxLen);
        return new float[]{pivotX + dx * tc, pivotY + dy * tc, len};
    }

    /**
     * @param upper     true = the part above the line, false = below
     * @param band      thickness of the red flesh band on the cut face (px)
     * @param bone      {x, y, length} from {@link #boneSpot}
     * @param boneThick thickness of the bone shaft (px)
     */
    static Bitmap half(Bitmap whole, float angle, float pivotX, float pivotY, boolean upper,
                       float band, float[] bone, float boneThick) {
        int bw = whole.getWidth(), bh = whole.getHeight();
        float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
        float nx = dy, ny = -dx;                     // normal pointing "up" from the cut line
        float s = upper ? 1f : -1f;
        float R = Math.max(bw, bh) * 2f;

        Bitmap out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas hc = new Canvas(out);

        Path side = new Path();
        side.moveTo(pivotX - dx * R, pivotY - dy * R);
        side.lineTo(pivotX + dx * R, pivotY + dy * R);
        side.lineTo(pivotX + dx * R + s * nx * R, pivotY + dy * R + s * ny * R);
        side.lineTo(pivotX - dx * R + s * nx * R, pivotY - dy * R + s * ny * R);
        side.close();

        hc.save();
        hc.clipPath(side);
        hc.drawBitmap(whole, 0, 0, null);
        hc.restore();

        // red flesh: SRC_ATOP paints only over existing pixels, so the glyph/sprite shape is kept
        Paint ap = new Paint();
        ap.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        ap.setColor(0xFFE5202E);
        hc.drawPath(band(pivotX, pivotY, dx, dy, nx, ny, s, R, band), ap);
        ap.setColor(0xFF9E0F1B);
        hc.drawPath(band(pivotX, pivotY, dx, dy, nx, ny, s, R, band * 0.35f), ap);

        // bone: half of it lives on each piece
        float len = Math.max(bone[2], boneThick * 3f);
        Paint bp = new Paint();
        bp.setStyle(Paint.Style.FILL);
        bp.setColor(0xFFFFFFF0);
        hc.save();
        hc.clipPath(side);
        hc.translate(bone[0], bone[1]);
        hc.rotate((float) Math.toDegrees(angle));
        RectF shaft = new RectF(-len / 2f, -boneThick / 2f, len / 2f, boneThick / 2f);
        hc.drawRect(shaft, bp);
        float k = boneThick * 0.8f;
        for (int e = -1; e <= 1; e += 2) {
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                float cx = e * len / 2f, cy = sgn * boneThick * 0.55f;
                hc.drawRect(cx - k / 2f, cy - k / 2f, cx + k / 2f, cy + k / 2f, bp);
            }
        }
        bp.setColor(0xFFB9B9A0);
        hc.drawRect(-len / 2f, boneThick / 2f - boneThick * 0.2f, len / 2f, boneThick / 2f, bp);
        hc.restore();
        return out;
    }

    private static Path band(float px, float py, float dx, float dy, float nx, float ny, float s, float R, float t) {
        Path bp = new Path();
        bp.moveTo(px - dx * R, py - dy * R);
        bp.lineTo(px + dx * R, py + dy * R);
        bp.lineTo(px + dx * R + s * nx * t, py + dy * R + s * ny * t);
        bp.lineTo(px - dx * R + s * nx * t, py - dy * R + s * ny * t);
        bp.close();
        return bp;
    }
}
