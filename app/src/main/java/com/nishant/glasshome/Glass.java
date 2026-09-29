package com.nishant.glasshome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Liquid Glass. Every glass surface shows the wallpaper behind it, blurred and slightly magnified
 * (refraction), under a tint whose strength follows the iOS 27 "Clear ↔ Tinted" slider, finished
 * with a darkened edge and a bright specular rim. Everything is drawn in screen coordinates.
 */
public class Glass {
    public Bitmap wall;
    private Bitmap blur;
    private BitmapShader blurShader;
    private int W = 1, H = 1;
    private final float dp;
    public boolean dark;
    /** 0 = ultra clear … 1 = fully tinted. */
    public float tint = 0.35f;

    private final Matrix sm = new Matrix();
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spec = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plain = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix gm = new Matrix();
    private final LinearGradient specGrad, sheenGrad;
    private final Path rp = new Path();
    private final RectF tmp = new RectF();

    public Glass(float density) {
        dp = density;
        edge.setStyle(Paint.Style.STROKE);
        spec.setStyle(Paint.Style.STROKE);
        specGrad = new LinearGradient(0, 0, 1, 1,
                new int[]{0xF2FFFFFF, 0x59FFFFFF, 0x0DFFFFFF, 0x14FFFFFF, 0x99FFFFFF},
                new float[]{0f, 0.18f, 0.5f, 0.78f, 1f}, Shader.TileMode.CLAMP);
        sheenGrad = new LinearGradient(0, 0, 0, 1,
                new int[]{0x2EFFFFFF, 0x08FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
        spec.setShader(specGrad);
        sheen.setShader(sheenGrad);
        shadow.setColor(0x00000000);
    }

    public void setWallpaper(Bitmap full) {
        wall = full;
        W = full.getWidth();
        H = full.getHeight();
        int bw = Math.max(8, W / 14), bh = Math.max(8, H / 14);
        Bitmap small = Bitmap.createScaledBitmap(full, bw, bh, true);
        int[] px = new int[bw * bh];
        small.getPixels(px, 0, bw, 0, 0, bw, bh);
        for (int i = 0; i < 3; i++) px = boxBlur(px, bw, bh, 2);
        // Boost saturation a little: real glass makes colours behind it look richer.
        for (int i = 0; i < px.length; i++) px[i] = saturate(px[i], 1.4f);
        blur = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888);
        if (small != full) small.recycle();
        blurShader = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        body.setShader(blurShader);
    }

    public float topLuminance() {
        if (blur == null) return 0;
        int n = 0; float sum = 0;
        int rows = Math.max(1, blur.getHeight() / 12);
        for (int y = 0; y < rows; y++) for (int x = 0; x < blur.getWidth(); x++) {
            int c = blur.getPixel(x, y);
            sum += (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
            n++;
        }
        return sum / n;
    }

    // ------------------------------------------------------------------ backgrounds

    public void drawWallpaper(Canvas c, float dim) {
        if (wall != null) c.drawBitmap(wall, 0, 0, plain);
        if (dim > 0) c.drawColor(Icons.alpha(0x000000, dim));
    }

    /** Full-screen frosted wallpaper (for folders, menus and search), faded in by {@code a}. */
    public void drawBackdrop(Canvas c, float a) {
        if (blurShader == null || a <= 0) return;
        sm.setScale(W / (float) blur.getWidth(), H / (float) blur.getHeight());
        blurShader.setLocalMatrix(sm);
        body.setAlpha(Math.round(255 * Math.min(1, a)));
        c.drawRect(0, 0, W, H, body);
        body.setAlpha(255);
        c.drawColor(Icons.alpha(dark ? 0x000000 : 0xFFFFFF, (dark ? 0.28f : 0.12f) * a));
    }

    // ------------------------------------------------------------------ glass surfaces

    private void placeShader(RectF r, float magnify) {
        sm.setScale(W / (float) blur.getWidth(), H / (float) blur.getHeight());
        sm.postScale(magnify, magnify, r.centerX(), r.centerY());
        blurShader.setLocalMatrix(sm);
    }

    public void panel(Canvas c, RectF r, float radius, float alpha) {
        panel(c, r, radius, alpha, 0f, 0);
    }

    /** A rounded glass panel. {@code extraTint} makes it more opaque (menus, alerts). */
    public void panel(Canvas c, RectF r, float radius, float alpha, float extraTint, int tintColor) {
        rp.reset();
        rp.addRoundRect(r, radius, radius, Path.Direction.CW);
        surface(c, rp, r, alpha, extraTint, tintColor, true);
    }

    /** Glass tile behind a Clear or Tinted icon. */
    public void iconTile(Canvas c, Path p, RectF r, int tintColor, float alpha) {
        surface(c, p, r, alpha, tintColor != 0 ? 0.15f : 0f, tintColor, false);
    }

    private void surface(Canvas c, Path p, RectF r, float alpha, float extraTint, int tintColor, boolean big) {
        if (alpha <= 0.01f) return;
        int a255 = Math.round(255 * alpha);
        if (blurShader != null) {
            placeShader(r, big ? 1.06f : 1.12f);
            body.setAlpha(a255);
            c.drawPath(p, body);
        }
        // Tint: the Clear ↔ Tinted slider.
        // Glass lifts what's behind it: a faint white veil always, then the Clear↔Tinted tint on top.
        float t = Math.min(1f, 0.03f + tint * (dark ? 0.5f : 0.6f) + extraTint);
        if (tintColor != 0) {
            fill.setColor(Icons.alpha(0x000000, (0.3f + 0.3f * tint) * alpha));
            c.drawPath(p, fill);
            fill.setColor(Icons.alpha(tintColor, 0.22f * alpha));
            c.drawPath(p, fill);
        } else {
            fill.setColor(Icons.alpha(0xFFFFFF, (dark ? 0.06f : 0.12f) * alpha));
            c.drawPath(p, fill);
            fill.setColor(Icons.alpha(dark ? 0x1C1C1E : 0xFFFFFF, t * alpha * (dark ? 0.9f : 0.75f)));
            c.drawPath(p, fill);
        }
        // Soft sheen across the top half.
        gm.setScale(1, r.height());
        gm.postTranslate(0, r.top);
        sheenGrad.setLocalMatrix(gm);
        sheen.setAlpha(a255);
        c.drawPath(p, sheen);
        // iOS 27: a darkened edge for definition...
        edge.setStrokeWidth((big ? 1.0f : 0.8f) * dp);
        edge.setColor(Icons.alpha(0x000000, (dark ? 0.16f : 0.07f) * alpha));
        c.drawPath(p, edge);
        // ...and a brighter specular highlight riding the rim.
        gm.setScale(r.width(), r.height());
        gm.postTranslate(r.left, r.top);
        specGrad.setLocalMatrix(gm);
        spec.setStrokeWidth((big ? 1.1f : 0.9f) * dp);
        spec.setAlpha(Math.round(a255 * (dark ? 0.7f : 0.95f)));
        c.save();
        c.clipPath(p);
        c.drawPath(p, spec);
        c.restore();
    }

    // ------------------------------------------------------------------ helpers

    static int[] boxBlur(int[] in, int w, int h, int r) {
        int[] tmp = new int[in.length], out = new int[in.length];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int rs = 0, gs = 0, bs = 0, n = 0;
            for (int k = -r; k <= r; k++) {
                int c = in[y * w + Math.min(w - 1, Math.max(0, x + k))];
                rs += (c >> 16) & 255; gs += (c >> 8) & 255; bs += c & 255; n++;
            }
            tmp[y * w + x] = 0xFF000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int rs = 0, gs = 0, bs = 0, n = 0;
            for (int k = -r; k <= r; k++) {
                int c = tmp[Math.min(h - 1, Math.max(0, y + k)) * w + x];
                rs += (c >> 16) & 255; gs += (c >> 8) & 255; bs += c & 255; n++;
            }
            out[y * w + x] = 0xFF000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
        }
        return out;
    }

    static int saturate(int c, float s) {
        float r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        float l = 0.299f * r + 0.587f * g + 0.114f * b;
        r = l + (r - l) * s; g = l + (g - l) * s; b = l + (b - l) * s;
        return 0xFF000000 | (cl(r) << 16) | (cl(g) << 8) | cl(b);
    }

    static int cl(float v) { return v < 0 ? 0 : v > 255 ? 255 : (int) v; }
}
