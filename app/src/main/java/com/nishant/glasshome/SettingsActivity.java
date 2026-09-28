package com.nishant.glasshome;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.IntConsumer;

/** iOS-style grouped settings: appearance, Liquid Glass, layout, wallpaper. */
public class SettingsActivity extends Activity {
    private static final int REQ_PHOTO = 7, REQ_ROLE = 8, REQ_STORAGE = 9;

    private Prefs prefs;
    private float dp;
    private boolean dark;
    private ScrollView scroll;
    private LinearLayout list;
    private int topInset, bottomInset;

    // iOS system colours
    private int bg, cell, sep, label, secondary, blue, red, green;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        dp = getResources().getDisplayMetrics().density;
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        scroll.setOnApplyWindowInsetsListener((v, ins) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars());
                topInset = s.top; bottomInset = s.bottom;
            } else { topInset = ins.getSystemWindowInsetTop(); bottomInset = ins.getSystemWindowInsetBottom(); }
            list.setPadding(0, topInset, 0, bottomInset + dpi(24));
            return ins;
        });
        setContentView(scroll);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        build();
    }

    private int dpi(float v) { return Math.round(v * dp); }

    private void colors() {
        dark = prefs.isDark(this);
        bg = dark ? 0xFF000000 : 0xFFF2F2F7;
        cell = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        sep = dark ? 0xFF38383A : 0xFFC6C6C8;
        label = dark ? 0xFFFFFFFF : 0xFF000000;
        secondary = dark ? 0x99EBEBF5 : 0x993C3C43;
        blue = dark ? 0xFF0A84FF : 0xFF007AFF;
        red = dark ? 0xFFFF453A : 0xFFFF3B30;
        green = dark ? 0xFF30D158 : 0xFF34C759;
        getWindow().getDecorView().setBackgroundColor(bg);
        scroll.setBackgroundColor(bg);
        View d = getWindow().getDecorView();
        int f = d.getSystemUiVisibility();
        d.setSystemUiVisibility(dark ? f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                : f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private void rebuild() {
        final int y = scroll.getScrollY();
        build();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void build() {
        colors();
        list.removeAllViews();

        TextView title = text("Home Settings", 34, label, true);
        title.setPadding(dpi(20), dpi(18), dpi(20), dpi(8));
        list.addView(title);

        // ---- Default home
        LinearLayout s0 = section(null);
        boolean isDefault = isDefaultHome();
        row(s0, isDefault ? "Glass Home is your Home Screen" : "Set as Default Home App",
                isDefault ? secondary : blue, isDefault ? null : v -> requestDefault());
        footer(isDefault ? "Press Home any time to come back here." :
                "Make Glass Home your launcher so the Home button and gesture open it.");

        // ---- Appearance
        LinearLayout s1 = section("APPEARANCE");
        segment(s1, "Mode", new String[]{"Automatic", "Light", "Dark"}, prefs.theme(), i -> { prefs.put("theme", i); rebuild(); });
        divider(s1);
        segment(s1, "App Icons", new String[]{"Default", "Dark", "Clear", "Tinted"}, prefs.iconStyle(), i -> { prefs.put("iconStyle", i); rebuild(); });
        if (prefs.iconStyle() == Prefs.ICON_TINTED) { divider(s1); swatches(s1); }
        divider(s1);
        LinearLayout gl = new LinearLayout(this);
        gl.setOrientation(LinearLayout.VERTICAL);
        gl.setPadding(dpi(16), dpi(12), dpi(16), dpi(12));
        gl.addView(text("Liquid Glass", 17, label, false));
        LinearLayout sl = new LinearLayout(this);
        sl.setGravity(Gravity.CENTER_VERTICAL);
        sl.addView(text("Clear", 13, secondary, false));
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(prefs.glass());
        sb.setProgressTintList(ColorStateList.valueOf(blue));
        sb.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(sep));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { prefs.put("glass", s.getProgress()); }
        });
        sl.addView(sb, new LinearLayout.LayoutParams(0, -2, 1));
        sl.addView(text("Tinted", 13, secondary, false));
        gl.addView(sl);
        s1.addView(gl);
        divider(s1);
        toggle(s1, "Show App Names", prefs.labels(), v -> prefs.put("labels", v));
        footer("Like iOS 27: slide toward Clear for more see-through glass, or toward Tinted for more contrast.");

        // ---- Home Screen
        LinearLayout s2 = section("HOME SCREEN");
        int grid = prefs.cols() == 5 ? 1 : prefs.rows() == 5 ? 2 : 0;
        segment(s2, "Grid", new String[]{"4 × 6", "5 × 6", "4 × 5"}, grid, i -> {
            prefs.put("cols", i == 1 ? 5 : 4);
            prefs.put("rows", i == 2 ? 5 : 6);
        });
        divider(s2);
        toggle(s2, "Clock & Calendar Widgets", prefs.widgets(), v -> prefs.put("widgets", v));
        divider(s2);
        segment(s2, "Newly Downloaded Apps", new String[]{"Add to Home Screen", "App Library Only"},
                prefs.newToHome() ? 0 : 1, i -> prefs.put("newToHome", i == 0));

        // ---- Wallpaper
        LinearLayout s3 = section("WALLPAPER");
        segment(s3, "Source", new String[]{"Built-in", "Photo", "System"}, prefs.wallMode(), i -> {
            if (i == Prefs.WALL_PHOTO && !Wallpapers.photoFile(this).exists()) { pickPhoto(); return; }
            if (i == Prefs.WALL_SYSTEM && !canReadWallpaper()) { askStorage(); }
            prefs.put("wallMode", i);
            rebuild();
        });
        if (prefs.wallMode() == Prefs.WALL_BUILTIN) { divider(s3); wallpaperStrip(s3); }
        divider(s3);
        row(s3, "Choose Photo…", blue, v -> pickPhoto());
        divider(s3);
        row(s3, "Use on Lock Screen Too", blue, v -> applyToSystem());
        if (prefs.wallMode() == Prefs.WALL_SYSTEM && !canReadWallpaper()) {
            divider(s3);
            row(s3, "Allow Access to System Wallpaper", blue, v -> askStorage());
        }
        footer("“System” blurs your phone’s own wallpaper into the glass; Android needs file access permission for that.");

        // ---- Reset
        LinearLayout s4 = section(null);
        row(s4, "Reset Home Screen Layout", red, v -> new AlertDialog.Builder(this, dark
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Reset Home Screen?")
                .setMessage("Your Home Screen will go back to its original layout. Folders will be removed.")
                .setPositiveButton("Reset", (d, w) -> prefs.resetLayout())
                .setNegativeButton("Cancel", null).show());
        footer("Tips: swipe down on the Home Screen to search. Touch and hold an app for quick actions, or hold an empty spot to edit. Drag an app onto another to make a folder.");
    }

    // ------------------------------------------------------------------ building blocks

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    private LinearLayout section(String header) {
        if (header != null) {
            TextView h = text(header, 13, secondary, false);
            h.setPadding(dpi(36), dpi(22), dpi(20), dpi(7));
            list.addView(h);
        } else {
            View spacer = new View(this);
            list.addView(spacer, new LinearLayout.LayoutParams(-1, dpi(22)));
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(cell);
        g.setCornerRadius(dpi(26));
        card.setBackground(g);
        card.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dpi(16), 0, dpi(16), 0);
        list.addView(card, lp);
        return card;
    }

    private void footer(String s) {
        TextView f = text(s, 13, secondary, false);
        f.setPadding(dpi(36), dpi(7), dpi(36), 0);
        list.addView(f);
    }

    private void divider(LinearLayout card) {
        View v = new View(this);
        v.setBackgroundColor(sep);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dpi(0.5f)));
        lp.setMargins(dpi(16), 0, 0, 0);
        card.addView(v, lp);
    }

    private void row(LinearLayout card, String s, int color, View.OnClickListener l) {
        TextView t = text(s, 17, color, false);
        t.setPadding(dpi(16), 0, dpi(16), 0);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dpi(50));
        if (l != null) {
            t.setOnClickListener(l);
            t.setBackground(pressable());
        }
        card.addView(t, new LinearLayout.LayoutParams(-1, dpi(50)));
    }

    private android.graphics.drawable.Drawable pressable() {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        GradientDrawable p = new GradientDrawable();
        p.setColor(dark ? 0xFF2C2C2E : 0xFFD1D1D6);
        s.addState(new int[]{android.R.attr.state_pressed}, p);
        return s;
    }

    private void toggle(LinearLayout card, String s, boolean on, java.util.function.Consumer<Boolean> change) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dpi(16), 0, dpi(12), 0);
        r.addView(text(s, 17, label, false), new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{green, dark ? 0xFF39393D : 0xFFE9E9EA}));
        sw.setTrackTintMode(android.graphics.PorterDuff.Mode.SRC);
        sw.setOnCheckedChangeListener((b, v) -> change.accept(v));
        r.addView(sw);
        card.addView(r, new LinearLayout.LayoutParams(-1, dpi(50)));
    }

    private void segment(LinearLayout card, String title, String[] opts, int sel, IntConsumer change) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dpi(16), dpi(12), dpi(16), dpi(12));
        r.addView(text(title, 17, label, false));
        Segmented s = new Segmented(this, opts, sel, change);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dpi(34));
        lp.topMargin = dpi(10);
        r.addView(s, lp);
        card.addView(r);
    }

    private void swatches(LinearLayout card) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dpi(16), dpi(12), dpi(16), dpi(12));
        r.addView(text("Tint", 17, label, false), new LinearLayout.LayoutParams(0, -2, 1));
        for (final int c : Prefs.TINTS) {
            View v = new View(this) {
                final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas cv) {
                    float rr = getWidth() / 2f;
                    if (prefs.tint() == c) {
                        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * dp); p.setColor(label);
                        cv.drawCircle(rr, rr, rr - dp, p);
                    }
                    p.setStyle(Paint.Style.FILL); p.setColor(c);
                    cv.drawCircle(rr, rr, rr - 5 * dp, p);
                    if (c == 0xFFFFFFFF) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp); p.setColor(sep); cv.drawCircle(rr, rr, rr - 5 * dp, p); }
                }
            };
            v.setOnClickListener(x -> { prefs.put("tint", c); rebuild(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpi(34), dpi(34));
            lp.leftMargin = dpi(4);
            r.addView(v, lp);
        }
        card.addView(r);
    }

    private void wallpaperStrip(LinearLayout card) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(this);
        r.setPadding(dpi(12), dpi(14), dpi(12), dpi(12));
        for (int i = 0; i < Wallpapers.NAMES.length; i++) {
            final int idx = i;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(Wallpapers.builtin(dpi(72), dpi(150), i, dark));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dpi(14)); }
            });
            iv.setClipToOutline(true);
            FrameLayout frame = new FrameLayout(this);
            int pad = dpi(3);
            frame.setPadding(pad, pad, pad, pad);
            if (prefs.wallStyle() == i) {
                GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(dpi(17));
                ring.setStroke(dpi(2.5f), blue);
                frame.setBackground(ring);
            }
            frame.addView(iv, new FrameLayout.LayoutParams(dpi(72), dpi(150)));
            frame.setOnClickListener(v -> { prefs.put("wallStyle", idx); prefs.put("wallMode", Prefs.WALL_BUILTIN); rebuild(); });
            col.addView(frame);
            TextView n = text(Wallpapers.NAMES[i], 12, secondary, false);
            n.setPadding(0, dpi(6), 0, 0);
            col.addView(n);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dpi(4), 0, dpi(4), 0);
            r.addView(col, lp);
        }
        hs.addView(r);
        card.addView(hs);
    }

    // ------------------------------------------------------------------ actions

    private boolean isDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME);
        }
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    private void requestDefault() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE);
                    return;
                }
            }
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    private void pickPhoto() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
        }
        i.setType("image/*");
        try { startActivityForResult(i, REQ_PHOTO); }
        catch (Exception e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.setType("image/*");
            try { startActivityForResult(g, REQ_PHOTO); } catch (Exception ignored) { }
        }
    }

    private boolean canReadWallpaper() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Exception ignored) { }
            }
        } else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
    }

    private void applyToSystem() {
        final Context app = getApplicationContext();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int w = dm.widthPixels, h = Math.max(dm.heightPixels, (int) (dm.widthPixels * 2.1f));
        Toast.makeText(this, "Setting wallpaper…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok;
            try {
                Bitmap b = Wallpapers.load(app, prefs, w, h, false);
                WallpaperManager.getInstance(app).setBitmap(b, null, true,
                        WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
                ok = true;
            } catch (Throwable t) { ok = false; }
            final boolean done = ok;
            runOnUiThread(() -> Toast.makeText(this, done ? "Lock Screen wallpaper set" : "Couldn’t set wallpaper", Toast.LENGTH_SHORT).show());
        }).start();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PHOTO && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri u = data.getData();
            new Thread(() -> {
                boolean ok = Wallpapers.savePhoto(getApplicationContext(), u);
                runOnUiThread(() -> {
                    if (ok) { prefs.put("wallMode", Prefs.WALL_PHOTO); rebuild(); }
                    else Toast.makeText(this, "Couldn’t open that picture", Toast.LENGTH_SHORT).show();
                });
            }).start();
        }
        if (req == REQ_ROLE) rebuild();
    }

    // ------------------------------------------------------------------ segmented control

    /** iOS segmented control. */
    private class Segmented extends View {
        private final String[] opts;
        private int sel;
        private final IntConsumer change;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Segmented(Context c, String[] opts, int sel, IntConsumer change) {
            super(c);
            this.opts = opts;
            this.sel = Math.max(0, Math.min(opts.length - 1, sel));
            this.change = change;
            p.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            r.set(0, 0, w, h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(dark ? 0x3D767680 : 0x1F767680);
            c.drawRoundRect(r, rad, rad, p);
            float sw = w / opts.length;
            r.set(sel * sw + 2 * dp, 2 * dp, (sel + 1) * sw - 2 * dp, h - 2 * dp);
            p.setColor(dark ? 0xFF636366 : 0xFFFFFFFF);
            p.setShadowLayer(3 * dp, 0, 1 * dp, 0x22000000);
            c.drawRoundRect(r, rad - 2 * dp, rad - 2 * dp, p);
            p.setShadowLayer(0, 0, 0, 0);
            p.setColor(label);
            p.setTextSize((opts.length > 3 || opts[0].length() > 12 ? 12.5f : 13.5f) * getResources().getDisplayMetrics().scaledDensity);
            for (int i = 0; i < opts.length; i++) {
                p.setTypeface(i == sel ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
                c.drawText(opts[i], sw * (i + 0.5f), h / 2 - (p.ascent() + p.descent()) / 2, p);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                int i = (int) Math.max(0, Math.min(opts.length - 1, e.getX() / (getWidth() / (float) opts.length)));
                if (i != sel) { sel = i; invalidate(); change.accept(i); }
            }
            return true;
        }
    }
}
