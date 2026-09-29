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

    // {top, bottom, disc1..disc4} — separate light and dark versions (dark is not just "dimmed").
    private static final int[][] LIGHT = {
            {0xC7DCFF, 0xF7E6F0, 0x3D7BFF, 0x8B6BFF, 0xFF86B0, 0xFFB066},
            {0xFFE0CC, 0xE9CCFF, 0xFF6A5C, 0xFF9E3D, 0xAE5BFF, 0x5A7BFF},
            {0xCFF5EF, 0xD8E8FF, 0x10BFA0, 0x2B8CFF, 0x74DD76, 0x00B1E3},
            {0xEDEFF2, 0xCFD3DA, 0x8C93A1, 0xB6BCC8, 0x5E6573, 0xDADDE3},
    };
    private static final int[][] DARK = {
            {0x04081C, 0x100620, 0x1E4BF0, 0x5B34E0, 0xE0327E, 0xF07A2E},
            {0x160812, 0x080A1C, 0xD8453A, 0xE07A1E, 0x8A3BE0, 0x3D5BE0},
            {0x021316, 0x040E26, 0x0C9C84, 0x1E6BE0, 0x3FAF4A, 0x0089B8},
            {0x08090B, 0x14161A, 0x3A3F4A, 0x565C68, 0x2A2E36, 0x6E7582},
    };
    // disc centre x (of width), centre y (of height), radius (of width)
    private static final float[][] DISCS = {{0.10f, 0.28f, 0.78f}, {0.98f, 0.50f, 0.72f}, {0.20f, 0.82f, 0.62f}, {0.88f, 1.04f, 0.55f}};

    public static Bitmap load(Context c, Prefs prefs, int w, int h, boolean dark) {
        Bitmap b = null;
        if (prefs.wallMode() == Prefs.WALL_PHOTO) b = photo(c, w, h);
        else if (prefs.wallMode() == Prefs.WALL_SYSTEM) b = system(c, w, h);
        if (b == null) b = builtin(w, h, prefs.wallStyle(), dark);
        return b;
    }

    /** Overlapping discs of coloured glass, each with a bright specular rim and a soft shadow. */
    public static Bitmap builtin(int w, int h, int style, boolean dark) {
        int[] pal = (dark ? DARK : LIGHT)[Math.max(0, Math.min(LIGHT.length - 1, style))];
        int gw = Math.max(16, w / 3), gh = Math.max(16, h / 3);
        int[] px = new int[gw * gh];
        float[] top = rgb(pal[0]), bot = rgb(pal[1]);
        float[][] disc = new float[4][];
        for (int i = 0; i < 4; i++) disc[i] = rgb(pal[2 + i]);
        float[] col = new float[3];
        for (int y = 0; y < gh; y++) {
            float t = smooth(y / (float) (gh - 1));
            for (int x = 0; x < gw; x++) {
                for (int k = 0; k < 3; k++) col[k] = top[k] + (bot[k] - top[k]) * t;
                for (int i = 0; i < 4; i++) {
                    float[] D = DISCS[i];
                    float dx = (x - D[0] * gw) / (D[2] * gw), dy = (y - D[1] * gh) / (D[2] * gw);
                    float d = (float) Math.sqrt(dx * dx + dy * dy) + 1e-6f;
                    float dirOut = (dx * 0.6f + dy * 0.8f) / d;          // toward bottom-right
                    if (d > 1f) {                                          // soft shadow outside
                        float e = (d - 1.04f) / 0.05f;
                        float sh = (float) Math.exp(-e * e) * Math.max(0, (dx + dy) / d) * (dark ? 0.25f : 0.12f);
                        for (int k = 0; k < 3; k++) col[k] *= 1 - sh;
                    }
                    float inside = 1f - smoothstep(0.985f, 1.0f, d);
                    if (inside > 0) {
                        float shade = Math.max(0.75f, Math.min(1.3f, 1 + 0.22f * (-(dx + dy) / 2)));
                        for (int k = 0; k < 3; k++) col[k] += (disc[i][k] * shade - col[k]) * inside * 0.88f;
                    }
                    float e = (d - 0.975f) / 0.012f;
                    float band = (float) Math.exp(-e * e);
                    if (band > 0.001f) {
                        float rim = band * (Math.max(0, -dirOut) * (dark ? 0.4f : 0.55f) + Math.max(0, dirOut) * 0.15f);
                        for (int k = 0; k < 3; k++) col[k] += (255 - col[k]) * rim;
                    }
                }
                px[y * gw + x] = 0xFF000000 | (Glass.cl(col[0]) << 16) | (Glass.cl(col[1]) << 8) | Glass.cl(col[2]);
            }
        }
        Bitmap small = Bitmap.createBitmap(px, gw, gh, Bitmap.Config.ARGB_8888);
        Bitmap out = Bitmap.createScaledBitmap(small, w, h, true);
        if (out != small) small.recycle();
        // A touch of grain stops banding on big smooth gradients.
        Bitmap mutable = out.isMutable() ? out : out.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(mutable);
        Random r = new Random(3);
        Paint p = new Paint();
        for (int i = 0; i < w * h / 120; i++) {
            p.setColor(r.nextBoolean() ? 0x08FFFFFF : 0x08000000);
            c.drawPoint(r.nextInt(w), r.nextInt(h), p);
        }
        return mutable;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    private static float smooth(float t) { return t * t * (3 - 2 * t); }

    private static float[] rgb(int c) { return new float[]{(c >> 16) & 255, (c >> 8) & 255, c & 255}; }


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
