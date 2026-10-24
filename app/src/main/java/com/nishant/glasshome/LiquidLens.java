package com.nishant.glasshome;

import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

/**
 * GPU liquid-glass lens (Android 13+). Draws whatever is behind a rounded glass shape the way a
 * thick convex lens would: the middle is gently magnified, the curved rim bends light in from
 * further out, colours split slightly at the very edge (dispersion), and light gathers along the
 * rim. Runs as an AGSL RuntimeShader on the GPU, so it is cheap enough to redraw every frame.
 *
 * The backdrop is supplied as a child shader ("src") whose coordinates are mapped with
 * {@code (drawCoord + origin) * scale}. Callers on older Android versions keep their old path.
 */
public final class LiquidLens {
    public static boolean supported() { return Build.VERSION.SDK_INT >= 33; }

    private static final String AGSL =
            "uniform shader src;\n" +
            "uniform float4 rect;\n" +      // left, top, width, height in draw coordinates
            "uniform float radius;\n" +
            "uniform float2 origin;\n" +    // added to draw coords before mapping to the source
            "uniform float2 scale;\n" +     // source units per draw unit
            "uniform float mag;\n" +        // magnification of the flat middle
            "uniform float bezel;\n" +      // width of the curved rim, px
            "uniform float strength;\n" +   // how far the rim bends light, px
            "uniform float disp;\n" +       // colour split at the rim, px
            "uniform float rimLight;\n" +   // brightness gathered along the rim
            "uniform float alpha;\n" +
            "float sdr(float2 p, float2 b, float r) {\n" +
            "  float2 q = abs(p) - b + r;\n" +
            "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n" +
            "}\n" +
            "half4 main(float2 fc) {\n" +
            "  float2 hs = rect.zw * 0.5;\n" +
            "  float2 c = rect.xy + hs;\n" +
            "  float2 p = fc - c;\n" +
            "  float rr = min(radius, min(hs.x, hs.y));\n" +
            "  float d = sdr(p, hs, rr);\n" +
            "  float2 n = float2(sdr(p + float2(1.0, 0.0), hs, rr) - sdr(p - float2(1.0, 0.0), hs, rr),\n" +
            "                    sdr(p + float2(0.0, 1.0), hs, rr) - sdr(p - float2(0.0, 1.0), hs, rr));\n" +
            "  float nl = length(n);\n" +
            "  n = nl > 0.0001 ? n / nl : float2(0.0, 0.0);\n" +
            "  float t = clamp(1.0 + d / max(bezel, 0.5), 0.0, 1.0);\n" +
            "  float bend = t * t * (3.0 - 2.0 * t);\n" +
            "  float2 q = p / mag + n * (bend * bend) * strength;\n" +
            "  float2 s = (c + q + origin) * scale;\n" +
            "  float2 cs = n * bend * disp * scale;\n" +
            "  half4 g = src.eval(s);\n" +
            "  half r = src.eval(s - cs).r;\n" +
            "  half b = src.eval(s + cs).b;\n" +
            "  half3 col = half3(r, g.g, b);\n" +
            "  col += half3(bend * bend * bend * rimLight);\n" +
            "  return half4(col * half(alpha), half(alpha));\n" +
            "}\n";

    private final RuntimeShader shader;
    public final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public LiquidLens() {
        shader = new RuntimeShader(AGSL);
        paint.setShader(shader);
    }

    /** Lens settings; sensible defaults for a key or tile. */
    public float mag = 1.08f, bezel = 10f, strength = 9f, disp = 1.2f, rimLight = 0.08f;

    /**
     * Prepares {@link #paint} for one glass shape. Draw the shape's path or round-rect with it.
     * @param src     backdrop shader (e.g. a BitmapShader over the blurred wallpaper)
     * @param r       shape bounds in draw coordinates
     * @param originX added to draw x before scaling into the source
     * @param scaleX  source units per draw unit
     */
    public Paint prepare(Shader src, RectF r, float radius, float originX, float originY,
                         float scaleX, float scaleY, float alpha) {
        shader.setInputShader("src", src);
        shader.setFloatUniform("rect", r.left, r.top, r.width(), r.height());
        shader.setFloatUniform("radius", radius);
        shader.setFloatUniform("origin", originX, originY);
        shader.setFloatUniform("scale", scaleX, scaleY);
        shader.setFloatUniform("mag", mag);
        shader.setFloatUniform("bezel", bezel);
        shader.setFloatUniform("strength", strength);
        shader.setFloatUniform("disp", disp);
        shader.setFloatUniform("rimLight", rimLight);
        shader.setFloatUniform("alpha", Math.max(0f, Math.min(1f, alpha)));
        return paint;
    }
}
