package com.nishant.glasshome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.Locale;

/** The two small iOS-style widgets on the first page: an analog Clock and a Calendar month. */
public class Widgets {
    public static final int CLOCK = 0, CALENDAR = 1;
    public static final int LIGHT = 0, DARK = 1, GLASS = 2;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

    public static float radius(RectF r) { return r.width() * 0.15f; }

    public void draw(Canvas c, RectF r, int kind, int style, Glass g, float alpha, float jiggle) {
        c.save();
        if (jiggle != 0) c.rotate(jiggle, r.centerX(), r.centerY());
        float rad = radius(r);
        if (style == GLASS) g.panel(c, r, rad, alpha);
        else {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Icons.alpha(style == DARK ? 0x1C1C1E : 0xFFFFFF, alpha));
            c.drawRoundRect(r, rad, rad, p);
        }
        int fg = style == LIGHT ? 0x000000 : 0xFFFFFF;
        if (kind == CLOCK) clock(c, r, style, fg, alpha);
        else calendar(c, r, fg, alpha);
        c.restore();
    }

    private void clock(Canvas c, RectF r, int style, int fg, float a) {
        float cx = r.centerX(), cy = r.centerY();
        float R = r.width() * 0.43f;
        // Face
        p.setStyle(Paint.Style.FILL);
        if (style == LIGHT) p.setColor(Icons.alpha(0xFFFFFF, a));
        else if (style == DARK) p.setColor(Icons.alpha(0x000000, a));
        else p.setColor(Icons.alpha(0x000000, 0.12f * a));
        c.drawCircle(cx, cy, R, p);
        // Minute ticks
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 60; i++) {
            if (i % 5 == 0) continue;
            double ang = Math.PI * 2 * i / 60;
            float s = (float) Math.sin(ang), co = (float) -Math.cos(ang);
            p.setStrokeWidth(R * 0.012f);
            p.setColor(Icons.alpha(fg, 0.35f * a));
            c.drawLine(cx + s * R * 0.9f, cy + co * R * 0.9f, cx + s * R * 0.95f, cy + co * R * 0.95f, p);
        }
        // Numbers
        t.setTypeface(medium);
        t.setTextSize(R * 0.21f);
        t.setTextAlign(Paint.Align.CENTER);
        t.setColor(Icons.alpha(fg, a));
        for (int i = 1; i <= 12; i++) {
            double ang = Math.PI * 2 * i / 12;
            float x = cx + (float) Math.sin(ang) * R * 0.72f;
            float y = cy - (float) Math.cos(ang) * R * 0.72f - (t.descent() + t.ascent()) / 2;
            c.drawText(String.valueOf(i), x, y, t);
        }
        Calendar now = Calendar.getInstance();
        float sec = now.get(Calendar.SECOND);
        float min = now.get(Calendar.MINUTE) + sec / 60f;
        float hr = (now.get(Calendar.HOUR) + min / 60f);
        hand(c, cx, cy, hr / 12f, R * 0.5f, R * 0.075f, Icons.alpha(fg, a));
        hand(c, cx, cy, min / 60f, R * 0.8f, R * 0.055f, Icons.alpha(fg, a));
        p.setStyle(Paint.Style.FILL);
        p.setColor(Icons.alpha(fg, a));
        c.drawCircle(cx, cy, R * 0.06f, p);
        hand(c, cx, cy, sec / 60f, R * 0.88f, R * 0.02f, Icons.alpha(0xFF9500, a));
        p.setStyle(Paint.Style.FILL);
        p.setColor(Icons.alpha(0xFF9500, a));
        c.drawCircle(cx, cy, R * 0.04f, p);
        p.setColor(Icons.alpha(style == LIGHT ? 0xFFFFFF : 0x000000, a));
        c.drawCircle(cx, cy, R * 0.018f, p);
    }

    private void hand(Canvas c, float cx, float cy, float frac, float len, float w, int col) {
        double ang = Math.PI * 2 * frac;
        float s = (float) Math.sin(ang), co = (float) -Math.cos(ang);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(w);
        p.setColor(col);
        c.drawLine(cx - s * len * 0.12f, cy - co * len * 0.12f, cx + s * len, cy + co * len, p);
    }

    private void calendar(Canvas c, RectF r, int fg, float a) {
        float s = r.width();
        float pad = s * 0.1f;
        Calendar now = Calendar.getInstance();
        int today = now.get(Calendar.DAY_OF_MONTH);
        String month = now.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault());
        t.setTextAlign(Paint.Align.LEFT);
        t.setTypeface(bold);
        t.setTextSize(s * 0.082f);
        t.setColor(Icons.alpha(0xFF3B30, a));
        c.drawText(month == null ? "" : month.toUpperCase(Locale.getDefault()), r.left + pad, r.top + pad + s * 0.07f, t);

        Calendar first = (Calendar) now.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int firstDow = first.getFirstDayOfWeek();
        int offset = (first.get(Calendar.DAY_OF_WEEK) - firstDow + 7) % 7;
        int days = now.getActualMaximum(Calendar.DAY_OF_MONTH);
        int rows = (offset + days + 6) / 7;

        float gridTop = r.top + pad + s * 0.17f;
        float cellW = (s - pad * 2 + s * 0.02f) / 7f;
        float gridBottom = r.bottom - pad * 0.8f;
        float rowH = (gridBottom - gridTop) / (rows + 1);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(s * 0.058f);
        t.setTypeface(bold);
        String[] names = new DateFormatSymbols(Locale.getDefault()).getShortWeekdays();
        for (int i = 0; i < 7; i++) {
            int dow = (firstDow - 1 + i) % 7 + 1;
            String n = names[dow];
            String letter = n == null || n.isEmpty() ? "" : n.substring(0, 1).toUpperCase(Locale.getDefault());
            t.setColor(Icons.alpha(fg, 0.5f * a));
            float x = r.left + pad - s * 0.01f + cellW * (i + 0.5f);
            c.drawText(letter, x, gridTop + rowH * 0.5f - (t.descent() + t.ascent()) / 2, t);
        }
        t.setTextSize(s * 0.066f);
        for (int d = 1; d <= days; d++) {
            int idx = offset + d - 1;
            int row = idx / 7 + 1, col = idx % 7;
            float x = r.left + pad - s * 0.01f + cellW * (col + 0.5f);
            float y = gridTop + rowH * (row + 0.5f);
            if (d == today) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Icons.alpha(0xFF3B30, a));
                c.drawCircle(x, y, Math.min(cellW, rowH) * 0.48f, p);
                t.setColor(Icons.alpha(0xFFFFFF, a));
                t.setTypeface(bold);
            } else {
                t.setColor(Icons.alpha(fg, a));
                t.setTypeface(medium);
            }
            c.drawText(String.valueOf(d), x, y - (t.descent() + t.ascent()) / 2, t);
        }
    }
}
