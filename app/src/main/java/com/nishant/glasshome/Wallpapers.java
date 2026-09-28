package com.nishant.glasshome;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Random;

/** Built-in wallpapers (original, generated on the phone), your own photo, or the system wallpaper. */
public class Wallpapers {
    public static final String[] NAMES = {"Aurora", "Dusk", "Lagoon", "Graphite"};

    // top, bottom, ribbon 1, ribbon 2, ribbon 3 — light variants; dark variants are derived.
    private static final int[][] PALETTES = {
            {0xFF6FA8FF, 0xFFF3B6A0, 0xFF3A6BFF, 0xFFB27CFF, 0xFFFF9F6B},
            {0xFFFF9FB8, 0xFF5B3FA8, 0xFFFF6F91, 0xFF8E5CFF, 0xFFFFC37A},
            {0xFF7FE3D6, 0xFF2D6FB0, 0xFF1FB5A3, 0xFF3E8EFF, 0xFFB6F28C},
            {0xFFB9BEC7, 0xFF3A3D44, 0xFF8A93A3, 0xFF5E6675, 0xFFD9DEE6},
    };

    public static Bitmap load(Context c, Prefs prefs, int w, int h, boolean dark) {
        Bitmap b = null;
        if (prefs.wallMode() == Prefs.WALL_PHOTO) b = photo(c, w, h);
        else if (prefs.wallMode() == Prefs.WALL_SYSTEM) b = system(c, w, h);
        if (b == null) b = builtin(w, h, prefs.wallStyle(), dark);
        return b;
    }

    /** Soft layered ribbons of colour, like light through stacked sheets of glass. */
    public static Bitmap builtin(int w, int h, int style, boolean dark) {
        int[] pal = PALETTES[Math.max(0, Math.min(PALETTES.length - 1, style))];
        int gw = Math.max(16, w / 6), gh = Math.max(16, h / 6);
        int[] px = new int[gw * gh];
        float[][] rib = {
                {0.30f, 0.10f, 5.2f, 0.4f, 0.13f},
                {0.55f, 0.13f, 3.9f, 2.1f, 0.16f},
                {0.80f, 0.09f, 6.3f, 4.0f, 0.12f},
        };
        for (int y = 0; y < gh; y++) {
            float ny = y / (float) (gh - 1);
            for (int x = 0; x < gw; x++) {
                float nx = x / (float) (gw - 1);
                float[] col = mix(pal[0], pal[1], smooth(ny));
                for (int i = 0; i < 3; i++) {
                    float[] rb = rib[i];
                    float cy = rb[0] + rb[1] * (float) Math.sin(nx * rb[2] + rb[3]) + 0.05f * (float) Math.sin(nx * 11f + i);
                    float d = (ny - cy) / rb[4];
                    float wgt = (float) Math.exp(-d * d) * 0.85f;
                    float[] rc = rgb(pal[2 + i]);
                    for (int k = 0; k < 3; k++) col[k] += (rc[k] - col[k]) * wgt;
                    // Bright glassy lip on the upper edge of each ribbon.
                    float e = (ny - (cy - rb[4] * 0.75f)) / (rb[4] * 0.18f);
                    float lip = (float) Math.exp(-e * e) * 0.35f;
                    for (int k = 0; k < 3; k++) col[k] += (255 - col[k]) * lip;
                }
                if (dark) for (int k = 0; k < 3; k++) col[k] *= 0.42f + 0.12f * (1 - ny);
                px[y * gw + x] = 0xFF000000 | (Glass.cl(col[0]) << 16) | (Glass.cl(col[1]) << 8) | Glass.cl(col[2]);
            }
        }
        px = Glass.boxBlur(px, gw, gh, 1);
        Bitmap small = Bitmap.createBitmap(px, gw, gh, Bitmap.Config.ARGB_8888);
        Bitmap out = Bitmap.createScaledBitmap(small, w, h, true);
        small.recycle();
        // A touch of grain stops banding on big smooth gradients.
        Bitmap mutable = out.isMutable() ? out : out.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(mutable);
        Random r = new Random(3);
        Paint p = new Paint();
        for (int i = 0; i < w * h / 90; i++) {
            p.setColor(r.nextBoolean() ? 0x0CFFFFFF : 0x0C000000);
            c.drawPoint(r.nextInt(w), r.nextInt(h), p);
        }
        return mutable;
    }

    private static float smooth(float t) { return t * t * (3 - 2 * t); }

    private static float[] rgb(int c) { return new float[]{(c >> 16) & 255, (c >> 8) & 255, c & 255}; }

    private static float[] mix(int a, int b, float t) {
        float[] x = rgb(a), y = rgb(b);
        return new float[]{x[0] + (y[0] - x[0]) * t, x[1] + (y[1] - x[1]) * t, x[2] + (y[2] - x[2]) * t};
    }

    // ------------------------------------------------------------------ photo

    public static File photoFile(Context c) { return new File(c.getFilesDir(), "wallpaper.jpg"); }

    /** Copies the chosen picture into the app (so it survives the original being deleted). */
    public static boolean savePhoto(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) return false;
            int max = 2400;
            if (Math.max(b.getWidth(), b.getHeight()) > max) {
                float s = max / (float) Math.max(b.getWidth(), b.getHeight());
                Bitmap sc = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
                b.recycle();
                b = sc;
            }
            try (FileOutputStream out = new FileOutputStream(photoFile(c))) {
                b.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Bitmap photo(Context c, int w, int h) {
        File f = photoFile(c);
        if (!f.exists()) return null;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        return b == null ? null : crop(b, w, h);
    }

    private static Bitmap system(Context c, int w, int h) {
        try {
            Drawable d = WallpaperManager.getInstance(c).getDrawable();
            if (d == null) return null;
            Bitmap b;
            if (d instanceof BitmapDrawable) b = ((BitmapDrawable) d).getBitmap();
            else {
                int iw = Math.max(1, d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : w);
                int ih = Math.max(1, d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : h);
                b = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(b);
                d.setBounds(0, 0, iw, ih);
                d.draw(cv);
            }
            return crop(b, w, h);
        } catch (Throwable t) {
            return null;  // no permission to read it
        }
    }

    static Bitmap crop(Bitmap b, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        float dw = b.getWidth() * s, dh = b.getHeight() * s;
        c.drawBitmap(b, null, new RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Also set it as the system (and lock screen) wallpaper so everything matches. */
    public static boolean applyToSystem(Context c, Bitmap b) {
        try {
            WallpaperManager.getInstance(c).setBitmap(b);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
