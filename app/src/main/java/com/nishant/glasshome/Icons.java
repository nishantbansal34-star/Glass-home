package com.nishant.glasshome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

/**
 * Turns Android app icons into iOS-style icons: full-bleed artwork inside a continuous-corner
 * "squircle", in the four iOS looks: Default, Dark, Clear and Tinted.
 */
public class Icons {
    private static final Path UNIT = squircle();
    private volatile int size = 0;
    private final Matrix m = new Matrix();
    private final Path tmpPath = new Path();
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint ph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int tintFor = 0;

    public int size() { return size; }
    public void setSize(int s) { size = s; }

    /** iOS "continuous corner" squircle as a superellipse with exponent 5, in a 0..1 box. */
    private static Path squircle() {
        Path p = new Path();
        int n = 160;
        double e = 2.0 / 5.0;
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            double c = Math.cos(t), s = Math.sin(t);
            float x = (float) (0.5 + 0.5 * Math.signum(c) * Math.pow(Math.abs(c), e));
            float y = (float) (0.5 + 0.5 * Math.signum(s) * Math.pow(Math.abs(s), e));
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        return p;
    }

    /** Squircle path placed at (x, y) with the given size. Returned path is reused: copy if kept. */
    public Path path(float x, float y, float s) {
        m.setScale(s, s);
        m.postTranslate(x, y);
        UNIT.transform(m, tmpPath);
        return tmpPath;
    }

    public static Path newPath(float x, float y, float s) {
        Matrix mm = new Matrix();
        mm.setScale(s, s);
        mm.postTranslate(x, y);
        Path p = new Path();
        UNIT.transform(mm, p);
        return p;
    }

    // ------------------------------------------------------------------ rendering (worker thread)

    public void render(AppInfo a, int s) {
        Drawable d = a.raw;
        if (d == null || s <= 0) return;
        Path clip = newPath(0, 0, s);
        Bitmap full = renderFull(d, s, clip), dark = renderDark(d, s, clip), glyph = renderGlyph(d, s, clip);
        // Publish "full" last: the UI thread treats it as the signal that all three are ready.
        a.dark = dark;
        a.glyph = glyph;
        a.renderedSize = s;
        a.full = full;
    }

    private static boolean isAdaptive(Drawable d) { return d instanceof AdaptiveIconDrawable; }

    /** Draw an adaptive layer so the visible 72/108 part fills the whole icon (iOS is full bleed). */
    private static void drawLayer(Canvas c, Drawable layer, int s) {
        if (layer == null) return;
        int extra = Math.round(s * 0.25f);
        layer.setBounds(-extra, -extra, s + extra, s + extra);
        layer.draw(c);
    }

    /** True when a legacy icon already fills its square (so we should not shrink it onto white). */
    private static boolean fullBleed(Drawable d, int s) {
        Bitmap probe = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(probe);
        d.setBounds(0, 0, s, s);
        d.draw(c);
        int in = Math.max(1, Math.round(s * 0.1f));
        int[][] pts = {{in, s / 2}, {s - in, s / 2}, {s / 2, in}, {s / 2, s - in}, {in, in}, {s - in, s - in}};
        int opaque = 0;
        for (int[] p : pts) if ((probe.getPixel(p[0], p[1]) >>> 24) > 200) opaque++;
        probe.recycle();
        return opaque >= 5;
    }

    private static void drawLegacy(Canvas c, Drawable d, int s, boolean bleed) {
        if (bleed) {
            int extra = Math.round(s * 0.06f);
            d.setBounds(-extra, -extra, s + extra, s + extra);
        } else {
            int in = Math.round(s * 0.16f);
            d.setBounds(in, in, s - in, s - in);
        }
        d.draw(c);
    }

    private static void mask(Canvas c, Path clip) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawPath(clip, p);
    }

    /** Thin glassy rim like iOS 26/27 icons: bright top-left edge, softer bottom-right. */
    private static void rim(Canvas c, Path clip, int s, float strength) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, s / 50f));
        p.setShader(new LinearGradient(0, 0, s, s,
                new int[]{alpha(0xFFFFFF, 0.55f * strength), alpha(0xFFFFFF, 0.08f * strength),
                        alpha(0xFFFFFF, 0f), alpha(0xFFFFFF, 0.28f * strength)},
                new float[]{0f, 0.35f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.save();
        c.clipPath(clip);
        c.drawPath(clip, p);
        c.restore();
    }

    static int alpha(int rgb, float a) {
        return (Math.max(0, Math.min(255, Math.round(a * 255))) << 24) | (rgb & 0xFFFFFF);
    }

    private Bitmap renderFull(Drawable d, int s, Path clip) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (isAdaptive(d)) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            drawLayer(c, a.getBackground(), s);
            drawLayer(c, a.getForeground(), s);
        } else {
            boolean bleed = fullBleed(d, s);
            if (!bleed) c.drawColor(0xFFFFFFFF);
            drawLegacy(c, d, s, bleed);
        }
        mask(c, clip);
        rim(c, clip, s, 1f);
        return b;
    }

    private Bitmap renderDark(Drawable d, int s, Path clip) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, 0, s, 0xFF3A3A3C, 0xFF141416, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, s, s, bg);
        if (isAdaptive(d)) {
            drawLayer(c, ((AdaptiveIconDrawable) d).getForeground(), s);
        } else {
            boolean bleed = fullBleed(d, s);
            if (bleed) {
                // A full-square legacy icon has no separate glyph: dim it instead.
                drawLegacy(c, d, s, true);
                c.drawColor(0x66000000);
            } else drawLegacy(c, d, s, false);
        }
        mask(c, clip);
        rim(c, clip, s, 0.8f);
        return b;
    }

    /** Light, colour-less glyph: used over glass for Clear icons and colourised for Tinted. */
    private Bitmap renderGlyph(Drawable d, int s, Path clip) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Drawable mono = null;
        if (Build.VERSION.SDK_INT >= 33 && isAdaptive(d)) mono = ((AdaptiveIconDrawable) d).getMonochrome();
        if (mono != null) {
            mono = mono.mutate();
            mono.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            drawLayer(c, mono, s);
        } else {
            if (isAdaptive(d)) drawLayer(c, ((AdaptiveIconDrawable) d).getForeground(), s);
            else drawLegacy(c, d, s, false);
            // Greyscale and lift toward white, keeping the shape's own shading.
            Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, false);
            b.eraseColor(0);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0f);
            ColorMatrix lift = new ColorMatrix(new float[]{
                    0.55f, 0, 0, 0, 120,
                    0, 0.55f, 0, 0, 120,
                    0, 0, 0.55f, 0, 120,
                    0, 0, 0, 1, 0});
            cm.postConcat(lift);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setColorFilter(new ColorMatrixColorFilter(cm));
            c.drawBitmap(copy, 0, 0, p);
            copy.recycle();
        }
        mask(c, clip);
        return b;
    }

    // ------------------------------------------------------------------ drawing (UI thread)

    /**
     * Draws an app icon with its top-left at (x, y). {@code glass} supplies the frosted tile for
     * Clear/Tinted icons. {@code pressed} darkens it like iOS does while your finger is down.
     */
    public void draw(Canvas c, AppInfo a, float x, float y, float s, int style, int tint,
                     Glass glass, boolean pressed, float alpha) {
        int al = Math.round(255 * alpha);
        if (a == null || a.full == null) {
            ph.setColor(alpha(0xFFFFFF, 0.25f * alpha));
            c.drawPath(path(x, y, s), ph);
            return;
        }
        r.set(x, y, x + s, y + s);
        if (style == Prefs.ICON_CLEAR || style == Prefs.ICON_TINTED) {
            boolean tinted = style == Prefs.ICON_TINTED;
            glass.iconTile(c, path(x, y, s), r, tinted ? tint : 0, alpha);
            if (tinted) {
                if (tintFor != tint) {
                    tintPaint.setColorFilter(new PorterDuffColorFilter(lighten(tint), PorterDuff.Mode.MULTIPLY));
                    tintFor = tint;
                }
                tintPaint.setAlpha(al);
                c.drawBitmap(a.glyph, null, r, tintPaint);
            } else {
                bmp.setAlpha(Math.round(al * 0.92f));
                c.drawBitmap(a.glyph, null, r, bmp);
            }
        } else {
            bmp.setAlpha(al);
            c.drawBitmap(style == Prefs.ICON_DARK ? a.dark : a.full, null, r, bmp);
        }
        if (pressed) {
            ph.setColor(0x59000000);
            c.drawPath(path(x, y, s), ph);
        }
    }

    private static int lighten(int c) {
        int rr = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        rr = rr + (255 - rr) / 4; g = g + (255 - g) / 4; b = b + (255 - b) / 4;
        return 0xFF000000 | (rr << 16) | (g << 8) | b;
    }
}
