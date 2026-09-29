package com.nishant.glasshome;

import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.os.SystemClock;
import android.text.Layout.Alignment;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.animation.AnimationUtils;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import com.nishant.glasshome.Layout.Item;

/**
 * The whole Home Screen, custom drawn: pages of icons, the floating glass dock, the Search pill,
 * widgets, jiggle-mode editing with drag & drop and folders, long-press menus, the App Library,
 * and iOS-style alerts.
 */
public class HomeView extends View {

    public interface Host {
        void launch(AppInfo a, Rect from);
        void openSpotlight(boolean library);
        void spotlightPull(float progress);
        void spotlightRelease(boolean open);
        void expandNotifications();
        void openSettings();
        void appDetails(AppInfo a, Rect from);
        void uninstall(AppInfo a);
        void startShortcut(ShortcutInfo s, Rect from);
        void openWidgetApp(int kind);
        void promptText(String title, String current, java.util.function.Consumer<String> done);
    }

    // ------------------------------------------------------------------ collaborators
    private final Host host;
    private final Prefs prefs;
    private final AppStore store;
    private final Icons icons;
    public final Glass glass;
    private final Widgets widgets = new Widgets();
    public final Layout lay = new Layout();

    // ------------------------------------------------------------------ settings snapshot
    private int iconStyle, tint, cols, rows;
    private boolean showLabels, showWidgets, dark;

    // ------------------------------------------------------------------ geometry
    private final float dp, sp;
    private int W, H, insetTop, insetBottom;
    private float side, cellW, rowH, icon, gridTop, cellTop, labelSize, dockPad;
    private final RectF dockRect = new RectF(), pillRect = new RectF();
    private float dockRadius;
    private int cap0, cap;

    // ------------------------------------------------------------------ paging & animation
    private float pagePos;                           // 0 .. L (L = App Library)
    private final Spring pageSpring = new Spring(320f, 1.0f);
    private boolean pageAnimating;
    private long lastFrame;
    private float time;                              // seconds, drives jiggle
    private long dotsUntil;
    private boolean editMode;
    private final Spring editSpring = new Spring(300f, 1f);
    private final Spring homeZoom = new Spring(190f, 0.95f);
    private int contentGen;
    /** GPU display-list cache for pages and dock (Android 10+). */
    private final PageCache cache = Build.VERSION.SDK_INT >= 29 ? new PageCache() : null;

    // ------------------------------------------------------------------ paints
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Typeface semibold = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final HashMap<String, String> ellipsized = new HashMap<>();
    private final RectF tmp = new RectF(), tmp2 = new RectF();

    // ------------------------------------------------------------------ touch
    private static final int T_NONE = 0, T_PENDING = 1, T_PAGING = 2, T_SPOT = 3, T_NOTIF = 4,
            T_LIB = 5, T_DRAG = 6, T_MENU = 7, T_MENU_ARMED = 8, T_FOLDER = 9, T_FOLDER_SCROLL = 10,
            T_ALERT = 11, T_IGNORE = 12;
    private int touch = T_NONE;
    private float downX, downY, basePos, baseScroll;
    private long downTime;
    private VelocityTracker vt;
    private final int slop;
    private Hit hit, pressed;
    private boolean notifFired;
    private float spotProgress;

    // ------------------------------------------------------------------ App Library
    private static class Cat { String name; List<AppInfo> apps = new ArrayList<>(); }
    private final ArrayList<Cat> cats = new ArrayList<>();
    private float libScroll;
    private final OverScroller libScroller;
    private final RectF libField = new RectF(), libBoxTmp = new RectF();

    // ------------------------------------------------------------------ drag & drop
    private Item dragItem;
    private float dragX, dragY, dragOffX, dragOffY;
    private List<Item> dragSource;
    private int dragSourceIndex;
    private boolean hoverDock;
    private int hoverPage = -1, hoverInsert = -1;
    private Item mergeCandidate, mergeTarget;
    private long mergeSince, edgeSince;
    private int edgeDir;
    private final Spring dragLift = new Spring(400f, 0.7f);

    // ------------------------------------------------------------------ folder overlay
    private Item folder;                 // null for App Library categories
    private List<String> folderKeys;
    private String folderTitle;
    private boolean folderReadOnly;
    private final RectF folderFrom = new RectF(), folderPanel = new RectF();
    private final Spring folderSpring = new Spring(320f, 0.86f);
    private boolean folderOpen;
    private float folderScroll, folderMaxScroll;
    private int folderCols;
    private float folderCellW, folderRowH, folderPad;

    // ------------------------------------------------------------------ menu
    private static class Row {
        String title; Drawable icon; int glyph; boolean red; boolean gapBefore; Runnable action;
        Row(String t, int g, boolean red, Runnable a) { title = t; glyph = g; this.red = red; action = a; }
    }
    private static final int G_NONE = 0, G_EDIT = 1, G_INFO = 2, G_MINUS = 3, G_PENCIL = 4, G_PLUS = 5, G_TRASH = 6, G_OUT = 7;
    private ArrayList<Row> menuRows;
    private final RectF menuRect = new RectF(), menuAnchor = new RectF();
    private Item menuItem; private AppInfo menuApp;
    private boolean menuAbove, menuVisible;
    private int menuPressed = -1;
    private final Spring menuSpring = new Spring(420f, 0.82f);
    private float menuRowH;

    // ------------------------------------------------------------------ alert
    private String alertTitle; private StaticLayout alertMsg;
    private ArrayList<Row> alertRows;
    private final RectF alertRect = new RectF();
    private int alertPressed = -1;
    private final Spring alertSpring = new Spring(500f, 0.8f);
    private boolean alertVisible;

    private final Runnable longPress = this::onLongPress;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (showWidgets && pagePos < 1f && isShown()) invalidate();
            postDelayed(this, 1000 - (System.currentTimeMillis() % 1000));
        }
    };

    public HomeView(Context c, Host host, Prefs prefs, AppStore store) {
        super(c);
        this.host = host;
        this.prefs = prefs;
        this.store = store;
        this.icons = store.icons;
        dp = getResources().getDisplayMetrics().density;
        sp = getResources().getDisplayMetrics().scaledDensity;
        glass = new Glass(dp);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        libScroller = new OverScroller(c);
        label.setColor(0xFFFFFFFF);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(semibold);
        label.setShadowLayer(3 * dp, 0, 0.5f * dp, 0x73000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        homeZoom.set(1f);
        readPrefs();
    }

    // ================================================================== setup

    public void readPrefs() {
        iconStyle = prefs.iconStyle();
        tint = prefs.tint();
        cols = prefs.cols();
        rows = prefs.rows();
        showLabels = prefs.labels();
        showWidgets = prefs.widgets();
        dark = prefs.isDark(getContext());
        glass.dark = dark;
        glass.tint = prefs.glass() / 100f;
        if (W > 0) geometry();
        contentChanged();
    }

    public void setInsets(int top, int bottom) {
        insetTop = top;
        insetBottom = bottom;
        if (W > 0) geometry();
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w; H = h;
        geometry();
    }

    public boolean isDarkTheme() { return dark; }

    private void geometry() {
        side = W * (cols >= 5 ? 0.045f : 0.07f);
        cellW = (W - 2 * side) / cols;
        icon = Math.min(cellW * (cols >= 5 ? 0.78f : 0.74f), 76 * dp);
        labelSize = Math.min(12f * sp, W * 0.031f);
        label.setTextSize(labelSize);
        ellipsized.clear();

        dockPad = icon * 0.26f;
        float dockH = icon + dockPad * 2;
        float bottom = H - Math.max(insetBottom, 14 * dp) - 4 * dp;
        float dm = W * 0.028f;
        dockRect.set(dm, bottom - dockH, W - dm, bottom);
        dockRadius = dockH * 0.42f;

        text.setTypeface(semibold);
        text.setTextSize(14 * sp);
        float pw = text.measureText("Search") + 52 * dp;
        float ph = 34 * dp;
        pillRect.set(W / 2f - pw / 2, dockRect.top - 14 * dp - ph, W / 2f + pw / 2, dockRect.top - 14 * dp);

        gridTop = insetTop + 14 * dp;
        float gridBottom = pillRect.top - 8 * dp;
        rowH = (gridBottom - gridTop) / rows;
        float cellH = icon + 6 * dp + labelSize * 1.3f;
        cellTop = Math.max(0, (rowH - cellH) / 2f);
        cap = cols * rows;
        cap0 = showWidgets ? cols * (rows - 2) : cap;
        contentGen++;

        int px = Math.round(icon);
        if (px != icons.size()) {
            icons.setSize(px);
            store.renderIcons(px);
        }
    }

    public int cap0() { return cap0; }
    public int cap() { return cap; }

    /** Load (or reload) the layout from prefs once apps are known. */
    public void loadLayout() {
        if (W == 0) { post(this::loadLayout); return; }
        boolean changed = lay.load(prefs.layout(), store, prefs, cap0, cap);
        lay.normalize(cap0, cap, !editMode);
        if (changed) save();
        buildLibrary();
        if (pagePos > libIndex()) pagePos = libIndex();
        contentChanged();
    }

    private void save() { prefs.saveLayout(lay.toJson()); contentGen++; }

    private int libIndex() { return lay.pages.size(); }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(tick); }

    // ================================================================== geometry helpers

    private float slotX(int slot) {
        int col = slot % cols;
        return side + col * cellW + (cellW - icon) / 2f;
    }

    private float slotY(int page, int slot) {
        int row = slot / cols + (page == 0 && showWidgets ? 2 : 0);
        return gridTop + row * rowH + cellTop;
    }

    private void widgetRect(int i, float ox, RectF out) {
        float left = side + (2 * i) * cellW + (cellW - icon) / 2f;
        float right = side + (2 * i + 1) * cellW + (cellW + icon) / 2f;
        float top = gridTop + cellTop;
        float size = Math.min(right - left, 2 * rowH - labelSize * 1.6f - 8 * dp);
        float cx = (left + right) / 2f;
        out.set(ox + cx - size / 2, top, ox + cx + size / 2, top + size);
    }

    private float dockSlotX(int i, int n) {
        float spacing = (dockRect.width() - dockPad * 1.2f) / Layout.DOCK_MAX;
        float start = dockRect.centerX() - spacing * n / 2f;
        return start + spacing * (i + 0.5f) - icon / 2f;
    }

    private float dockY() { return dockRect.centerY() - icon / 2f; }

    private int widgetStyle() {
        if (iconStyle == Prefs.ICON_CLEAR || iconStyle == Prefs.ICON_TINTED) return Widgets.GLASS;
        if (iconStyle == Prefs.ICON_DARK || dark) return Widgets.DARK;
        return Widgets.LIGHT;
    }

    // ================================================================== drawing

    @Override protected void onDraw(Canvas c) {
        // Vsync-aligned frame time (not "whenever onDraw ran") keeps motion even.
        long now = AnimationUtils.currentAnimationTimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.max(0.001f, Math.min(0.05f, (now - lastFrame) / 1000f));
        lastFrame = now;
        time += dt;
        boolean anim = step(dt);
        anim |= homeZoom.step(dt);

        float z = 1f - homeZoom.value;                       // 1 → just came back from an app
        c.save();
        if (z > 0.001f) c.scale(1 + 0.05f * z, 1 + 0.05f * z, W / 2f, H / 2f);
        glass.drawWallpaper(c, dark && prefs.wallMode() != Prefs.WALL_BUILTIN ? 0.22f : 0f);
        c.restore();

        c.save();
        if (z > 0.001f) c.scale(1 + 0.12f * z, 1 + 0.12f * z, W / 2f, H * 0.45f);
        int L = libIndex();
        int p0 = (int) Math.floor(pagePos), p1 = (int) Math.ceil(pagePos);
        for (int p = Math.max(0, p0); p <= Math.min(L, p1); p++) {
            float ox = (p - pagePos) * W;
            if (p < L) anim |= drawPage(c, p, ox, dt);
            else drawLibrary(c, ox);
        }
        anim |= drawDock(c, dt);
        drawPill(c, now);
        c.restore();
        if (editSpring.value > 0.01f) drawEditButtons(c);
        if (dragItem != null) { drawDragged(c); anim = true; }
        if (folderSpring.value > 0.001f || folderOpen) drawFolder(c);
        if (menuSpring.value > 0.001f || menuVisible) drawMenu(c);
        if (alertSpring.value > 0.001f || alertVisible) drawAlert(c);

        if (anim || editMode || now < dotsUntil + 300) postInvalidateOnAnimation();
        else lastFrame = 0;
    }

    /** Called when the user comes back from an app: the Home Screen settles in like iOS. */
    public void playReturn() {
        homeZoom.set(0);
        homeZoom.target = 1f;
        lastFrame = 0;
        invalidate();
    }

    /** Something drawn on the pages changed (icons, layout, settings): re-record cached pages. */
    public void contentChanged() {
        contentGen++;
        invalidate();
    }

    /** Advance springs. Returns true if anything is still moving. */
    private boolean step(float dt) {
        boolean a = false;
        if (pageAnimating) {
            pageAnimating = pageSpring.step(dt);
            pagePos = pageSpring.value;
            a |= pageAnimating;
        }
        a |= editSpring.step(dt);
        a |= folderSpring.step(dt);
        a |= menuSpring.step(dt);
        a |= alertSpring.step(dt);
        a |= dragLift.step(dt);
        if (!libScroller.isFinished() && libScroller.computeScrollOffset()) {
            libScroll = libScroller.getCurrY();
            a = true;
        }
        if (!folderOpen && folderSpring.value <= 0.001f) folderKeys = null;
        if (!menuVisible && menuSpring.value <= 0.001f) menuRows = null;
        if (!alertVisible && alertSpring.value <= 0.001f) alertRows = null;
        if (dragItem != null) a |= dragTick();
        return a;
    }

    private float jiggle(Item it) {
        if (editSpring.value < 0.01f || it == dragItem) return 0;
        return (float) Math.sin(time * 2 * Math.PI * 3.1 + it.phase) * 1.7f * editSpring.value;
    }

    private boolean drawPage(Canvas c, int p, float ox, float dt) {
        boolean moving = layoutPage(p, dt);
        boolean live = moving || editSpring.value > 0.001f || dragItem != null || pressedOn(p);
        if (!live && cache != null && c.isHardwareAccelerated()) {
            long key = ((long) contentGen << 32) | (p == 0 && showWidgets ? (System.currentTimeMillis() / 1000) & 0xFFFFFFFFL : 0);
            final int page = p;
            cache.draw(c, p, key, ox, W, H, rc -> paintPage(rc, page, 0));
        } else paintPage(c, p, ox);
        return moving;
    }

    /** Move icons toward their slots (smoothly, when things reflow). Returns true while moving. */
    private boolean layoutPage(int p, float dt) {
        boolean moving = false;
        List<Item> items = lay.pages.get(p);
        float k = 1f - (float) Math.exp(-dt * 16f);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            int slot = i;
            if (dragItem != null && !hoverDock && hoverPage == p && hoverInsert >= 0 && i >= hoverInsert) slot = i + 1;
            float tx = slotX(slot), ty = slotY(p, slot);
            if (!it.placed) { it.x = tx; it.y = ty; it.placed = true; }
            else if (Math.abs(it.x - tx) > 0.5f || Math.abs(it.y - ty) > 0.5f) {
                it.x += (tx - it.x) * k; it.y += (ty - it.y) * k; moving = true;
            } else { it.x = tx; it.y = ty; }
        }
        return moving;
    }

    private boolean pressedOn(int p) {
        if (pressed == null || touch != T_PENDING) return false;
        if (pressed.type == Hit.WIDGET) return p == 0;
        return pressed.type == Hit.ITEM && pressed.page == p;
    }

    private void paintPage(Canvas c, int p, float ox) {
        if (p == 0 && showWidgets) {
            int ws = widgetStyle();
            for (int i = 0; i < 2; i++) {
                widgetRect(i, ox, tmp);
                float j = editSpring.value > 0.01f ? (float) Math.sin(time * 2 * Math.PI * 3.1 + i * 2) * 1.2f * editSpring.value : 0;
                boolean isPressed = pressed != null && pressed.type == Hit.WIDGET && pressed.index == i && touch == T_PENDING;
                widgets.draw(c, tmp, i, ws, glass, 1f, j);
                if (isPressed) {
                    fill.setColor(0x33000000);
                    c.drawRoundRect(tmp, Widgets.radius(tmp), Widgets.radius(tmp), fill);
                }
                if (showLabels) drawLabel(c, i == 0 ? "Clock" : "Calendar", tmp.centerX(), tmp.bottom + 6 * dp, 1f);
                if (editSpring.value > 0.01f && i == 0) drawBadge(c, tmp.left, tmp.top, editSpring.value);
            }
        }
        List<Item> items = lay.pages.get(p);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            int slot = i;
            if (dragItem != null && !hoverDock && hoverPage == p && hoverInsert >= 0 && i >= hoverInsert) slot = i + 1;
            boolean hidden = slot >= (p == 0 ? cap0 : cap);
            drawItem(c, it, ox + it.x, it.y, hidden ? 0f : 1f, true, isPressed(it));
        }
    }

    private boolean isPressed(Item it) {
        return pressed != null && pressed.item == it && (touch == T_PENDING) && !editMode;
    }

    private boolean drawDock(Canvas c, float dt) {
        boolean moving = false;
        final int n = lay.dock.size() + (dragItem != null && hoverDock && hoverInsert >= 0 ? 1 : 0);
        float k = 1f - (float) Math.exp(-dt * 16f);
        for (int i = 0; i < lay.dock.size(); i++) {
            Item it = lay.dock.get(i);
            int slot = i;
            if (dragItem != null && hoverDock && hoverInsert >= 0 && i >= hoverInsert) slot = i + 1;
            float tx = dockSlotX(slot, n), ty = dockY();
            if (!it.placed) { it.x = tx; it.y = ty; it.placed = true; }
            else if (Math.abs(it.x - tx) > 0.5f || Math.abs(it.y - ty) > 0.5f) {
                it.x += (tx - it.x) * k; it.y += (ty - it.y) * k; moving = true;
            } else { it.x = tx; it.y = ty; }
        }
        boolean live = moving || editSpring.value > 0.001f || dragItem != null
                || (pressed != null && touch == T_PENDING && pressed.type == Hit.ITEM && pressed.page < 0);
        if (!live && cache != null && c.isHardwareAccelerated()) {
            cache.draw(c, -1, contentGen, 0, W, H, this::paintDock);
        } else paintDock(c);
        return moving;
    }

    private void paintDock(Canvas c) {
        glass.panel(c, dockRect, dockRadius, 1f);
        for (int i = 0; i < lay.dock.size(); i++) {
            Item it = lay.dock.get(i);
            drawItem(c, it, it.x, it.y, 1f, false, isPressed(it));
        }
    }

    private void drawItem(Canvas c, Item it, float x, float y, float alpha, boolean withLabel, boolean isPressed) {
        if (alpha <= 0) return;
        float j = jiggle(it);
        c.save();
        if (j != 0) c.rotate(j, x + icon / 2, y + icon / 2);
        if (mergeTarget == it) {
            float g = icon * 0.14f;
            fill.setColor(0x4DFFFFFF);
            c.drawPath(icons.path(x - g, y - g, icon + 2 * g), fill);
        }
        if (it.folder) drawFolderIcon(c, it, x, y, icon, alpha);
        else icons.draw(c, store.get(it.key), x, y, icon, iconStyle, tint, glass, isPressed, alpha);
        if (withLabel && showLabels) {
            String name = it.folder ? it.title : labelOf(it.key);
            drawLabel(c, name, x + icon / 2, y + icon + 5 * dp, alpha * (1f - 0f));
        }
        if (editSpring.value > 0.01f && it != dragItem) drawBadge(c, x, y, editSpring.value);
        c.restore();
    }

    private String labelOf(String key) {
        AppInfo a = store.get(key);
        return a == null ? "" : a.label;
    }

    private void drawLabel(Canvas c, String s, float cx, float top, float alpha) {
        String e = ellipsized.get(s);
        if (e == null) {
            e = TextUtils.ellipsize(s, label, cellW - 2 * dp, TextUtils.TruncateAt.END).toString();
            ellipsized.put(s, e);
        }
        label.setAlpha(Math.round(255 * alpha));
        c.drawText(e, cx, top - label.ascent(), label);
        label.setAlpha(255);
    }

    private void drawFolderIcon(Canvas c, Item f, float x, float y, float s, float alpha) {
        tmp.set(x, y, x + s, y + s);
        glass.iconTile(c, icons.path(x, y, s), tmp, 0, alpha);
        float pad = s * 0.14f, gap = s * 0.055f, mini = (s - 2 * pad - 2 * gap) / 3f;
        int n = Math.min(9, f.apps.size());
        for (int i = 0; i < n; i++) {
            float mx = x + pad + (i % 3) * (mini + gap), my = y + pad + (i / 3) * (mini + gap);
            icons.draw(c, store.get(f.apps.get(i)), mx, my, mini, iconStyle, tint, glass, false, alpha);
        }
    }

    private void drawBadge(Canvas c, float x, float y, float a) {
        float r = 11 * dp * a;
        float cx = x + 2 * dp, cy = y + 2 * dp;
        fill.setColor(dark ? 0xF03A3A3C : 0xF0E5E5EA);
        c.drawCircle(cx, cy, r, fill);
        stroke.setStrokeWidth(2.2f * dp);
        stroke.setColor(dark ? 0xFFFFFFFF : 0xFF000000);
        c.drawLine(cx - r * 0.45f, cy, cx + r * 0.45f, cy, stroke);
    }

    private void drawPill(Canvas c, long now) {
        float a = Math.max(0, Math.min(1, libIndex() - pagePos));
        if (a <= 0.01f) return;
        boolean dots = now < dotsUntil || touch == T_PAGING || pageAnimating || dragItem != null;
        int n = libIndex();
        float w = pillRect.width();
        if (dots) w = Math.max(n * 12 * dp + 24 * dp, 60 * dp);
        tmp.set(pillRect.centerX() - w / 2, pillRect.top, pillRect.centerX() + w / 2, pillRect.bottom);
        glass.panel(c, tmp, tmp.height() / 2, a);
        int fg = dark ? 0xFFFFFF : 0x1C1C1E;
        if (dots) {
            float sx = tmp.centerX() - (n - 1) * 6 * dp;
            for (int i = 0; i < n; i++) {
                float d = Math.abs(pagePos - i);
                fill.setColor(Icons.alpha(fg, a * (d < 0.5f ? 0.95f : 0.35f)));
                c.drawCircle(sx + i * 12 * dp, tmp.centerY(), 3.6f * dp, fill);
            }
        } else {
            text.setTypeface(semibold);
            text.setTextSize(14 * sp);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(Icons.alpha(fg, 0.85f * a));
            float tw = text.measureText("Search");
            float gx = tmp.centerX() - (tw + 18 * dp) / 2;
            magnifier(c, gx + 6 * dp, tmp.centerY(), 5.5f * dp, Icons.alpha(fg, 0.85f * a));
            c.drawText("Search", gx + 18 * dp, tmp.centerY() - (text.ascent() + text.descent()) / 2, text);
        }
    }

    private void magnifier(Canvas c, float cx, float cy, float r, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(1.8f * dp);
        c.drawCircle(cx - r * 0.2f, cy - r * 0.2f, r * 0.75f, stroke);
        c.drawLine(cx + r * 0.35f, cy + r * 0.35f, cx + r * 0.95f, cy + r * 0.95f, stroke);
    }

    private final RectF editBtn = new RectF(), doneBtn = new RectF();

    private void drawEditButtons(Canvas c) {
        float a = editSpring.value;
        float h = 34 * dp, top = insetTop + 2 * dp - (1 - a) * 20 * dp;
        text.setTypeface(semibold);
        text.setTextSize(15 * sp);
        text.setTextAlign(Paint.Align.CENTER);
        float ew = text.measureText("Edit") + 32 * dp, dw = text.measureText("Done") + 32 * dp;
        editBtn.set(16 * dp, top, 16 * dp + ew, top + h);
        doneBtn.set(W - 16 * dp - dw, top, W - 16 * dp, top + h);
        int fg = dark ? 0xFFFFFF : 0x1C1C1E;
        glass.panel(c, editBtn, h / 2, a);
        glass.panel(c, doneBtn, h / 2, a);
        text.setColor(Icons.alpha(fg, a));
        c.drawText("Edit", editBtn.centerX(), editBtn.centerY() - (text.ascent() + text.descent()) / 2, text);
        text.setColor(Icons.alpha(dark ? 0x0A84FF : 0x007AFF, a));
        text.setTypeface(bold);
        c.drawText("Done", doneBtn.centerX(), doneBtn.centerY() - (text.ascent() + text.descent()) / 2, text);
    }

    private void drawDragged(Canvas c) {
        float s = 1f + 0.12f * dragLift.value;
        float x = dragX - dragOffX, y = dragY - dragOffY;
        c.save();
        c.scale(s, s, x + icon / 2, y + icon / 2);
        if (dragItem.folder) drawFolderIcon(c, dragItem, x, y, icon, 0.95f);
        else icons.draw(c, store.get(dragItem.key), x, y, icon, iconStyle, tint, glass, false, 0.95f);
        c.restore();
    }

    // ------------------------------------------------------------------ App Library

    public void buildLibrary() {
        cats.clear();
        List<String> recents = prefs.recents();
        Cat sug = new Cat();
        sug.name = "Suggestions";
        for (String k : recents) { AppInfo a = store.get(k); if (a != null && sug.apps.size() < 8) sug.apps.add(a); }
        if (!sug.apps.isEmpty()) cats.add(sug);
        Cat rec = new Cat();
        rec.name = "Recently Added";
        ArrayList<AppInfo> byDate = new ArrayList<>(store.apps);
        byDate.sort((a, b) -> Long.compare(b.installed, a.installed));
        for (AppInfo a : byDate) { if (rec.apps.size() >= 8) break; rec.apps.add(a); }
        if (!rec.apps.isEmpty()) cats.add(rec);
        String[] order = {"Social", "Entertainment", "Productivity & Finance", "Utilities", "Photo & Video",
                "Information & Reading", "Travel", "Games", "Other"};
        LinkedHashMap<String, Cat> map = new LinkedHashMap<>();
        for (String o : order) { Cat c = new Cat(); c.name = o; map.put(o, c); }
        for (AppInfo a : store.apps) {
            Cat c = map.get(a.category);
            if (c != null) c.apps.add(a);
        }
        for (Cat c : map.values()) if (!c.apps.isEmpty()) cats.add(c);
    }

    private float libSide() { return W * 0.075f; }
    private float libBox() { return (W - 2 * libSide() - W * 0.065f) / 2f; }
    private float libRowH() { return libBox() + labelSize * 1.4f + 20 * dp; }
    private float libTop() { return libField.bottom + 22 * dp; }

    private float libMaxScroll() {
        int rowsN = (cats.size() + 1) / 2;
        float content = libTop() + rowsN * libRowH() + (H - dockRect.top) + 16 * dp;
        return Math.max(0, content - H);
    }

    private void libBoxRect(int i, float ox, RectF out) {
        float B = libBox();
        int col = i % 2, row = i / 2;
        float x = ox + libSide() + col * (B + W * 0.065f);
        float y = libTop() + row * libRowH() - libScroll;
        out.set(x, y, x + B, y + B);
    }

    /** Slot rect (0..3) inside a category box; slot 3 may be a 2×2 cluster. */
    private void libSlot(RectF box, int slot, RectF out) {
        float B = box.width(), pad = B * 0.09f, s = (B - 3 * pad) / 2f;
        float x = box.left + pad + (slot % 2) * (s + pad), y = box.top + pad + (slot / 2) * (s + pad);
        out.set(x, y, x + s, y + s);
    }

    private void drawLibrary(Canvas c, float ox) {
        libField.set(ox + libSide() - W * 0.02f, insetTop + 10 * dp, ox + W - libSide() + W * 0.02f, insetTop + 10 * dp + 40 * dp);
        c.save();
        c.clipRect(ox, libField.bottom + 4 * dp, ox + W, H);
        for (int i = 0; i < cats.size(); i++) {
            libBoxRect(i, ox, tmp);
            if (tmp.bottom < 0 || tmp.top > H) continue;
            Cat cat = cats.get(i);
            float B = tmp.width();
            glass.panel(c, tmp, B * 0.2f, 1f);
            RectF box = libBoxTmp;
            box.set(tmp);
            int big = cat.apps.size() > 4 ? 3 : Math.min(4, cat.apps.size());
            for (int s = 0; s < big; s++) {
                libSlot(box, s, tmp2);
                icons.draw(c, cat.apps.get(s), tmp2.left, tmp2.top, tmp2.width(), iconStyle, tint, glass,
                        pressed != null && pressed.type == Hit.LIB && pressed.index == i && pressed.slot == s && touch == T_PENDING, 1f);
            }
            if (cat.apps.size() > 4) {
                libSlot(box, 3, tmp2);
                float g = tmp2.width() * 0.1f, m = (tmp2.width() - g) / 2f;
                for (int s = 0; s < Math.min(4, cat.apps.size() - 3); s++) {
                    icons.draw(c, cat.apps.get(3 + s), tmp2.left + (s % 2) * (m + g), tmp2.top + (s / 2) * (m + g), m,
                            iconStyle, tint, glass, false, 1f);
                }
            }
            text.setTypeface(semibold);
            text.setTextSize(labelSize * 1.05f);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(0xFFFFFFFF);
            text.setShadowLayer(3 * dp, 0, 0.5f * dp, 0x73000000);
            String n = TextUtils.ellipsize(cat.name, text, B, TextUtils.TruncateAt.END).toString();
            c.drawText(n, box.centerX(), box.bottom + 8 * dp - text.ascent(), text);
            text.setShadowLayer(0, 0, 0, 0);
        }
        c.restore();
        glass.panel(c, libField, libField.height() / 2, 1f, 0.1f, 0);
        int fg = dark ? 0xFFFFFF : 0x1C1C1E;
        magnifier(c, libField.left + 22 * dp, libField.centerY(), 7 * dp, Icons.alpha(fg, 0.6f));
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(17 * sp);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(Icons.alpha(fg, 0.6f));
        c.drawText("App Library", libField.left + 38 * dp, libField.centerY() - (text.ascent() + text.descent()) / 2, text);
    }

    // ------------------------------------------------------------------ folder overlay

    public void openFolder(Item f, List<String> keys, String title, boolean readOnly, RectF from) {
        folder = f;
        folderKeys = keys;
        folderTitle = title;
        folderReadOnly = readOnly;
        folderFrom.set(from);
        folderScroll = 0;
        folderOpen = true;
        folderSpring.target = 1f;
        folderLayout();
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        invalidate();
    }

    private void folderLayout() {
        int n = folderKeys == null ? 0 : folderKeys.size();
        folderCols = folderReadOnly && n > 9 ? 4 : 3;
        float pw = W * (folderCols == 4 ? 0.88f : 0.8f);
        folderPad = pw * 0.07f;
        folderCellW = (pw - 2 * folderPad) / folderCols;
        folderRowH = icon + 6 * dp + labelSize * 1.3f + 16 * dp;
        int rowsN = Math.max(1, (n + folderCols - 1) / folderCols);
        float full = rowsN * folderRowH + 2 * folderPad - 12 * dp;
        float ph = Math.min(full, H * 0.58f);
        folderMaxScroll = Math.max(0, full - ph);
        float cy = H * 0.47f;
        folderPanel.set(W / 2f - pw / 2, cy - ph / 2, W / 2f + pw / 2, cy + ph / 2);
    }

    public void closeFolder() {
        if (!folderOpen) return;
        folderOpen = false;
        folderSpring.target = 0f;
        // Refresh where the folder icon is, in case it moved.
        invalidate();
    }

    private float folderIconX(int i) {
        return folderPanel.left + folderPad + (i % folderCols) * folderCellW + (folderCellW - icon) / 2f;
    }

    private float folderIconY(int i) {
        return folderPanel.top + folderPad + (i / folderCols) * folderRowH - folderScroll;
    }

    private void drawFolder(Canvas c) {
        if (folderKeys == null) return;
        float p = Math.max(0, folderSpring.value);
        float pc = Math.min(1, p);
        glass.drawBackdrop(c, pc);
        float l = lerp(folderFrom.left, folderPanel.left, p), t = lerp(folderFrom.top, folderPanel.top, p);
        float r = lerp(folderFrom.right, folderPanel.right, p), b = lerp(folderFrom.bottom, folderPanel.bottom, p);
        tmp.set(l, t, r, b);
        float rad = lerp(icon * 0.225f, folderPanel.width() * 0.12f, pc);
        glass.panel(c, tmp, rad, 1f, 0.12f * pc, 0);

        // Title
        text.setTypeface(bold);
        text.setTextSize(30 * sp);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(Icons.alpha(0xFFFFFF, pc));
        text.setShadowLayer(4 * dp, 0, 1 * dp, Icons.alpha(0x000000, 0.35f * pc));
        float ty = folderPanel.top - 22 * dp;
        String title = TextUtils.ellipsize(folderTitle, text, folderPanel.width(), TextUtils.TruncateAt.END).toString();
        if (editMode && !folderReadOnly) {
            float tw = text.measureText(title);
            tmp2.set(folderPanel.left - 10 * dp, ty + text.ascent() - 6 * dp, folderPanel.left + tw + 14 * dp, ty + text.descent() + 6 * dp);
            glass.panel(c, tmp2, 14 * dp, pc);
        }
        c.drawText(title, folderPanel.left, ty, text);
        text.setShadowLayer(0, 0, 0, 0);

        // Contents, scaled with the panel as it opens.
        float sx = tmp.width() / folderPanel.width(), sy = tmp.height() / folderPanel.height();
        c.save();
        c.clipRect(tmp);
        c.translate(tmp.left, tmp.top);
        c.scale(sx, sy);
        c.translate(-folderPanel.left, -folderPanel.top);
        float ca = Math.max(0, Math.min(1, (p - 0.35f) / 0.65f));
        for (int i = 0; i < folderKeys.size(); i++) {
            float x = folderIconX(i), y = folderIconY(i);
            if (y + folderRowH < folderPanel.top || y > folderPanel.bottom) continue;
            AppInfo a = store.get(folderKeys.get(i));
            boolean pr = pressed != null && pressed.type == Hit.FOLDER_ITEM && pressed.index == i && touch == T_FOLDER;
            icons.draw(c, a, x, y, icon, iconStyle, tint, glass, pr, ca);
            if (a != null) drawLabel(c, a.label, x + icon / 2, y + icon + 5 * dp, ca);
        }
        c.restore();
    }

    // ------------------------------------------------------------------ menu

    private void openMenu(Hit h) {
        menuItem = h.item;
        menuApp = h.app;
        menuAnchor.set(h.rect);
        ArrayList<Row> rowsL = new ArrayList<>();
        final Rect from = new Rect();
        h.rect.round(from);
        if (menuApp != null) {
            for (final ShortcutInfo si : shortcuts(menuApp)) {
                CharSequence t = si.getShortLabel();
                Row r = new Row(t == null ? "" : t.toString(), G_NONE, false, () -> host.startShortcut(si, from));
                try { r.icon = store.launcherApps().getShortcutIconDrawable(si, getResources().getDisplayMetrics().densityDpi); } catch (Exception ignored) { }
                rowsL.add(r);
            }
        }
        boolean first = true;
        final AppInfo app = menuApp;
        final Item item = menuItem;
        if (h.type == Hit.FOLDER_ITEM && !folderReadOnly && folder != null) {
            final String key = app.key;
            Row r = new Row("Remove from Folder", G_OUT, false, () -> removeFromFolder(key));
            r.gapBefore = !rowsL.isEmpty(); first = false;
            rowsL.add(r);
        }
        if (h.type == Hit.ITEM) {
            if (item.folder) {
                Row r = new Row("Rename", G_PENCIL, false, () -> rename(item));
                rowsL.add(r); first = false;
            }
            Row e = new Row("Edit Home Screen", G_EDIT, false, this::enterEdit);
            e.gapBefore = first && !rowsL.isEmpty(); first = false;
            rowsL.add(e);
        }
        if (app != null) {
            Row info = new Row("App Info", G_INFO, false, () -> host.appDetails(app, from));
            info.gapBefore = first && !rowsL.isEmpty(); first = false;
            rowsL.add(info);
            if (h.type == Hit.LIB || h.type == Hit.FOLDER_ITEM && folderReadOnly) {
                if (!lay.contains(app.key)) rowsL.add(new Row("Add to Home Screen", G_PLUS, false, () -> addToHome(app.key)));
                rowsL.add(new Row("Delete App", G_TRASH, true, () -> host.uninstall(app)));
            } else {
                rowsL.add(new Row("Remove App", G_MINUS, true, () -> confirmRemove(item, app)));
            }
        } else if (item != null && item.folder) {
            rowsL.add(new Row("Remove Folder", G_MINUS, true, () -> confirmRemove(item, null)));
        }
        menuRows = rowsL;
        menuRowH = 46 * dp;
        float mw = Math.min(W * 0.66f, 270 * dp);
        float mh = 0;
        for (Row r : rowsL) mh += menuRowH + (r.gapBefore ? 8 * dp : 0);
        float left = Math.max(16 * dp, Math.min(W - 16 * dp - mw, menuAnchor.left + (menuAnchor.centerX() < W / 2f ? 0 : menuAnchor.width() - mw)));
        float below = menuAnchor.bottom + 14 * dp + (h.type == Hit.ITEM || h.type == Hit.FOLDER_ITEM ? labelSize * 1.3f : 0);
        menuAbove = below + mh > H - 24 * dp;
        float top = menuAbove ? menuAnchor.top - 14 * dp - mh : below;
        if (top < insetTop + 8 * dp) top = insetTop + 8 * dp;
        menuRect.set(left, top, left + mw, top + mh);
        menuVisible = true;
        menuPressed = -1;
        menuSpring.target = 1f;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private List<ShortcutInfo> shortcuts(AppInfo a) {
        ArrayList<ShortcutInfo> out = new ArrayList<>();
        try {
            LauncherApps la = store.launcherApps();
            if (!la.hasShortcutHostPermission()) return out;
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(a.pkg());
            q.setActivity(a.cn);
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<ShortcutInfo> l = la.getShortcuts(q, a.user);
            if (l != null) {
                l = new ArrayList<>(l);
                l.sort((x, y) -> Integer.compare(x.getRank(), y.getRank()));
                for (ShortcutInfo s : l) { if (out.size() >= 4) break; if (s.isEnabled()) out.add(s); }
            }
        } catch (Exception ignored) { }
        return out;
    }

    public void closeMenu() {
        menuVisible = false;
        menuSpring.target = 0f;
        invalidate();
    }

    private void drawMenu(Canvas c) {
        if (menuRows == null) return;
        float p = Math.max(0, menuSpring.value), pc = Math.min(1, p);
        glass.drawBackdrop(c, pc * 0.95f);
        // Lifted icon or widget
        float s = 1f + 0.06f * pc;
        c.save();
        c.scale(s, s, menuAnchor.centerX(), menuAnchor.centerY());
        if (menuItem != null && menuItem.folder) drawFolderIcon(c, menuItem, menuAnchor.left, menuAnchor.top, menuAnchor.width(), 1f);
        else if (menuApp != null) icons.draw(c, menuApp, menuAnchor.left, menuAnchor.top, menuAnchor.width(), iconStyle, tint, glass, false, 1f);
        c.restore();
        // Panel grows out of the icon.
        float sc = 0.5f + 0.5f * p;
        float px = menuAnchor.centerX() < W / 2f ? menuRect.left : menuRect.right;
        float py = menuAbove ? menuRect.bottom : menuRect.top;
        c.save();
        c.scale(sc, sc, px, py);
        glass.panel(c, menuRect, 26 * dp, pc, 0.5f, 0);
        int fg = dark ? 0xFFFFFF : 0x000000;
        float y = menuRect.top;
        text.setTextSize(16 * sp);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < menuRows.size(); i++) {
            Row r = menuRows.get(i);
            if (r.gapBefore) {
                fill.setColor(Icons.alpha(dark ? 0x000000 : 0x3C3C43, 0.18f * pc));
                c.drawRect(menuRect.left, y, menuRect.right, y + 8 * dp, fill);
                y += 8 * dp;
            } else if (i > 0) {
                fill.setColor(Icons.alpha(dark ? 0xFFFFFF : 0x3C3C43, 0.16f * pc));
                c.drawRect(menuRect.left + 16 * dp, y, menuRect.right, y + Math.max(1, 0.6f * dp), fill);
            }
            if (i == menuPressed) {
                fill.setColor(Icons.alpha(dark ? 0xFFFFFF : 0x000000, 0.1f * pc));
                c.save();
                c.clipRect(menuRect.left, y, menuRect.right, y + menuRowH);
                c.drawRoundRect(menuRect, 26 * dp, 26 * dp, fill);
                c.restore();
            }
            int col = r.red ? 0xFF3B30 : fg;
            text.setColor(Icons.alpha(col, pc));
            String t = TextUtils.ellipsize(r.title, text, menuRect.width() - 72 * dp, TextUtils.TruncateAt.END).toString();
            c.drawText(t, menuRect.left + 18 * dp, y + menuRowH / 2 - (text.ascent() + text.descent()) / 2, text);
            float gx = menuRect.right - 30 * dp, gy = y + menuRowH / 2;
            if (r.icon != null) {
                int is = Math.round(22 * dp);
                r.icon.setBounds(Math.round(gx - is / 2f), Math.round(gy - is / 2f), Math.round(gx + is / 2f), Math.round(gy + is / 2f));
                r.icon.setAlpha(Math.round(255 * pc));
                r.icon.draw(c);
            } else glyph(c, r.glyph, gx, gy, 9 * dp, Icons.alpha(col, pc));
            y += menuRowH;
        }
        c.restore();
    }

    private void glyph(Canvas c, int g, float cx, float cy, float r, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(1.7f * dp);
        fill.setColor(color);
        switch (g) {
            case G_EDIT: {
                // phone outline with a small grid of apps
                tmp2.set(cx - r * 0.7f, cy - r, cx + r * 0.7f, cy + r);
                c.drawRoundRect(tmp2, r * 0.3f, r * 0.3f, stroke);
                float q = r * 0.22f;
                for (int i = 0; i < 4; i++) c.drawCircle(cx + (i % 2 == 0 ? -q : q), cy - r * 0.3f + (i / 2) * q * 2, q * 0.55f, fill);
                break;
            }
            case G_INFO:
                c.drawCircle(cx, cy, r, stroke);
                c.drawLine(cx, cy - r * 0.05f, cx, cy + r * 0.5f, stroke);
                c.drawCircle(cx, cy - r * 0.45f, 1.3f * dp, fill);
                break;
            case G_MINUS:
                c.drawCircle(cx, cy, r, stroke);
                c.drawLine(cx - r * 0.45f, cy, cx + r * 0.45f, cy, stroke);
                break;
            case G_PLUS:
                c.drawCircle(cx, cy, r, stroke);
                c.drawLine(cx - r * 0.45f, cy, cx + r * 0.45f, cy, stroke);
                c.drawLine(cx, cy - r * 0.45f, cx, cy + r * 0.45f, stroke);
                break;
            case G_PENCIL:
                c.drawLine(cx - r * 0.8f, cy + r * 0.8f, cx + r * 0.6f, cy - r * 0.6f, stroke);
                c.drawLine(cx - r * 0.8f, cy + r * 0.8f, cx - r * 0.5f, cy + r * 0.2f, stroke);
                c.drawLine(cx + r * 0.3f, cy - r * 0.9f, cx + r * 0.9f, cy - r * 0.3f, stroke);
                break;
            case G_TRASH:
                c.drawLine(cx - r * 0.9f, cy - r * 0.6f, cx + r * 0.9f, cy - r * 0.6f, stroke);
                tmp2.set(cx - r * 0.65f, cy - r * 0.6f, cx + r * 0.65f, cy + r);
                c.drawRoundRect(tmp2, r * 0.2f, r * 0.2f, stroke);
                c.drawLine(cx - r * 0.3f, cy - r * 0.95f, cx + r * 0.3f, cy - r * 0.95f, stroke);
                break;
            case G_OUT:
                tmp2.set(cx - r, cy - r * 0.6f, cx + r * 0.3f, cy + r);
                c.drawRoundRect(tmp2, r * 0.25f, r * 0.25f, stroke);
                c.drawLine(cx - r * 0.2f, cy + r * 0.1f, cx + r, cy - r * 1.0f, stroke);
                c.drawLine(cx + r, cy - r, cx + r * 0.3f, cy - r, stroke);
                c.drawLine(cx + r, cy - r, cx + r, cy - r * 0.3f, stroke);
                break;
            default:
        }
    }

    // ------------------------------------------------------------------ alert

    private void showAlert(String title, String msg, ArrayList<Row> buttons) {
        alertTitle = title;
        alertRows = buttons;
        float w = Math.min(W * 0.74f, 300 * dp);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(13.5f * sp);
        tp.setColor(dark ? 0xFFFFFFFF : 0xFF000000);
        alertMsg = StaticLayout.Builder.obtain(msg, 0, msg.length(), tp, Math.round(w - 40 * dp))
                .setAlignment(Alignment.ALIGN_CENTER).build();
        float h = 22 * dp + 22 * sp + 8 * dp + alertMsg.getHeight() + 20 * dp + buttons.size() * 48 * dp + 8 * dp;
        alertRect.set(W / 2f - w / 2, H / 2f - h / 2, W / 2f + w / 2, H / 2f + h / 2);
        alertVisible = true;
        alertPressed = -1;
        alertSpring.set(0.85f);
        alertSpring.target = 1f;
        invalidate();
    }

    private void closeAlert() {
        alertVisible = false;
        alertSpring.target = 0f;
        invalidate();
    }

    private float alertButtonsTop() { return alertRect.bottom - alertRows.size() * 48 * dp - 8 * dp; }

    private void drawAlert(Canvas c) {
        if (alertRows == null) return;
        float p = alertVisible ? 1f : Math.max(0, (alertSpring.value - 0.85f) / 0.15f);
        c.drawColor(Icons.alpha(0x000000, 0.25f * p));
        float s = alertSpring.value;
        c.save();
        c.scale(s, s, alertRect.centerX(), alertRect.centerY());
        glass.panel(c, alertRect, 30 * dp, p, 0.55f, 0);
        int fg = dark ? 0xFFFFFF : 0x000000;
        text.setTypeface(bold);
        text.setTextSize(17 * sp);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Icons.alpha(fg, p));
        float y = alertRect.top + 22 * dp - text.ascent();
        c.drawText(TextUtils.ellipsize(alertTitle, text, alertRect.width() - 32 * dp, TextUtils.TruncateAt.END).toString(), alertRect.centerX(), y, text);
        c.save();
        c.translate(alertRect.left + 20 * dp, y + text.descent() + 8 * dp);
        alertMsg.getPaint().setAlpha(Math.round(255 * p));
        alertMsg.draw(c);
        c.restore();
        float by = alertButtonsTop();
        for (int i = 0; i < alertRows.size(); i++) {
            Row r = alertRows.get(i);
            tmp2.set(alertRect.left + 14 * dp, by + i * 48 * dp + 4 * dp, alertRect.right - 14 * dp, by + (i + 1) * 48 * dp - 2 * dp);
            fill.setColor(Icons.alpha(dark ? 0xFFFFFF : 0x000000, (i == alertPressed ? 0.2f : 0.08f) * p));
            c.drawRoundRect(tmp2, tmp2.height() / 2, tmp2.height() / 2, fill);
            text.setTypeface(r.gapBefore ? bold : semibold);
            text.setTextSize(16 * sp);
            text.setColor(Icons.alpha(r.red ? 0xFF3B30 : (r.gapBefore ? fg : (dark ? 0x0A84FF : 0x007AFF)), p));
            c.drawText(r.title, tmp2.centerX(), tmp2.centerY() - (text.ascent() + text.descent()) / 2, text);
        }
        c.restore();
    }

    // ================================================================== actions

    public void enterEdit() {
        if (editMode) return;
        editMode = true;
        editSpring.target = 1f;
        closeMenu();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    public void exitEdit() {
        if (!editMode) return;
        editMode = false;
        editSpring.target = 0f;
        lay.normalize(cap0, cap, true);
        save();
        if (pagePos > libIndex()) animateTo(libIndex());
        else if (!pageAnimating) animateTo(Math.round(Math.min(pagePos, libIndex())));
        invalidate();
    }

    public boolean isEditing() { return editMode; }

    private void animateTo(float page) {
        pageSpring.value = pagePos;
        pageSpring.target = page;
        pageAnimating = true;
        invalidate();
    }

    /** Home button / gesture: peel back one layer, iOS-style. Returns true if something was closed. */
    public boolean goHome(boolean toFirstPage) {
        if (alertVisible) { closeAlert(); return true; }
        if (menuVisible) { closeMenu(); return true; }
        if (folderOpen) { closeFolder(); return true; }
        if (editMode) { exitEdit(); return true; }
        if (toFirstPage && (pagePos != 0 || libScroll != 0)) {
            animateTo(0);
            libScroller.forceFinished(true);
            libScroll = 0;
            dotsUntil = SystemClock.uptimeMillis() + 900;
            return true;
        }
        return false;
    }

    private void confirmRemove(final Item item, final AppInfo app) {
        ArrayList<Row> b = new ArrayList<>();
        String name = item != null && item.folder ? item.title : app != null ? app.label : "";
        if (app != null) b.add(new Row("Delete App", 0, true, () -> host.uninstall(app)));
        b.add(new Row("Remove from Home Screen", 0, false, () -> removeFromHome(item, app)));
        Row cancel = new Row("Cancel", 0, false, null);
        cancel.gapBefore = true;   // bold "Cancel"
        b.add(cancel);
        showAlert("Remove “" + name + "”?",
                item != null && item.folder ? "The apps in this folder will stay in your App Library."
                        : "Removing from Home Screen will keep the app in your App Library.", b);
    }

    private void removeFromHome(Item item, AppInfo app) {
        List<String> removed = prefs.removed();
        if (item != null && item.folder) {
            removed.addAll(item.apps);
            for (List<Item> p : lay.pages) p.remove(item);
            lay.dock.remove(item);
        } else if (app != null) {
            removed.add(app.key);
            lay.removeKey(app.key);
        }
        prefs.setRemoved(removed);
        lay.normalize(cap0, cap, !editMode);
        save();
        invalidate();
    }

    private void addToHome(String key) {
        List<String> removed = prefs.removed();
        removed.remove(key);
        prefs.setRemoved(removed);
        lay.append(Item.app(key), cap0, cap);
        save();
        invalidate();
    }

    private void removeFromFolder(String key) {
        if (folder == null) return;
        folder.apps.remove(key);
        folderKeys = new ArrayList<>(folder.apps);
        int page = Math.max(0, Math.min(libIndex() - 1, Math.round(pagePos)));
        ArrayList<Item> pg = lay.pages.get(page);
        if (folder.apps.size() <= 1) {
            dissolve(folder);
            closeFolder();
        } else folderLayout();
        pg.add(Item.app(key));
        lay.normalize(cap0, cap, !editMode);
        save();
        invalidate();
    }

    /** A folder with one app left becomes that app, in place. */
    private void dissolve(Item f) {
        List<List<Item>> all = new ArrayList<>(lay.pages);
        all.add(lay.dock);
        for (List<Item> l : all) {
            int i = l.indexOf(f);
            if (i < 0) continue;
            if (f.apps.isEmpty()) l.remove(i);
            else {
                Item a = Item.app(f.apps.get(0));
                a.x = f.x; a.y = f.y; a.placed = f.placed;
                l.set(i, a);
            }
        }
    }

    private void rename(final Item f) {
        host.promptText("Rename Folder", f.title, s -> {
            if (s != null && !s.trim().isEmpty()) {
                f.title = s.trim();
                if (folder == f) folderTitle = f.title;
                ellipsized.clear();
                save();
                invalidate();
            }
        });
    }

    private static String folderNameFor(AppInfo a, AppInfo b) {
        if (a != null && b != null && a.category.equals(b.category) && !a.category.equals("Other")) {
            switch (a.category) {
                case "Productivity & Finance": return "Productivity";
                case "Information & Reading": return "Reading";
                case "Photo & Video": return "Creativity";
                default: return a.category;
            }
        }
        if (a != null && !a.category.equals("Other")) return a.category.split(" ")[0];
        return "Folder";
    }

    // ================================================================== hit testing

    private static class Hit {
        static final int EMPTY = 0, ITEM = 1, WIDGET = 2, PILL = 3, EDIT = 4, DONE = 5, BADGE = 6,
                LIB_FIELD = 7, LIB = 8, LIB_LABEL = 9, FOLDER_ITEM = 10, FOLDER_TITLE = 11, FOLDER_OUT = 12, FOLDER_BG = 13;
        int type;
        Item item; AppInfo app;
        List<Item> list; int index = -1; int slot = -1; int page = -1;
        final RectF rect = new RectF();
    }

    private Hit hitTest(float x, float y) {
        Hit h = new Hit();
        int L = libIndex();
        if (editSpring.value > 0.5f) {
            if (inflate(editBtn, 6).contains(x, y)) { h.type = Hit.EDIT; return h; }
            if (inflate(doneBtn, 6).contains(x, y)) { h.type = Hit.DONE; return h; }
        }
        // Dock
        for (int i = 0; i < lay.dock.size(); i++) {
            Item it = lay.dock.get(i);
            if (editMode && badgeHit(it.x, it.y, x, y)) return itemHit(h, Hit.BADGE, it, lay.dock, i, -1, it.x, it.y);
            if (x >= it.x - 6 * dp && x <= it.x + icon + 6 * dp && y >= it.y - 6 * dp && y <= it.y + icon + 6 * dp)
                return itemHit(h, Hit.ITEM, it, lay.dock, i, -1, it.x, it.y);
        }
        if (dockRect.contains(x, y)) { h.type = Hit.EMPTY; return h; }
        int page = Math.round(pagePos);
        float ox = (page - pagePos) * W;
        if (page >= L) {
            if (libField.contains(x, y)) { h.type = Hit.LIB_FIELD; return h; }
            for (int i = 0; i < cats.size(); i++) {
                libBoxRect(i, ox, tmp);
                if (y < libField.bottom) break;
                RectF box = new RectF(tmp);
                if (box.contains(x, y)) {
                    Cat cat = cats.get(i);
                    for (int s = 0; s < 4; s++) {
                        libSlot(box, s, tmp2);
                        if (!inflate(tmp2, 4).contains(x, y)) continue;
                        h.index = i; h.slot = s;
                        if (s == 3 && cat.apps.size() > 4) { h.type = Hit.LIB_LABEL; h.rect.set(box); return h; }
                        if (s < cat.apps.size()) { h.type = Hit.LIB; h.app = cat.apps.get(s); h.rect.set(tmp2); return h; }
                    }
                    h.type = Hit.EMPTY; return h;
                }
                if (x >= box.left && x <= box.right && y > box.bottom && y < box.bottom + labelSize * 1.4f + 14 * dp) {
                    h.type = Hit.LIB_LABEL; h.index = i; h.rect.set(box); return h;
                }
            }
            h.type = Hit.EMPTY; return h;
        }
        if (page >= 0 && page < L) {
            if (page == 0 && showWidgets) {
                for (int i = 0; i < 2; i++) {
                    widgetRect(i, ox, tmp);
                    if (editMode && i == 0 && Math.hypot(x - tmp.left, y - tmp.top) < 18 * dp) { h.type = Hit.BADGE; h.index = -2; return h; }
                    if (tmp.contains(x, y)) { h.type = Hit.WIDGET; h.index = i; h.rect.set(tmp); return h; }
                }
            }
            List<Item> items = lay.pages.get(page);
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                float ix = ox + it.x, iy = it.y;
                if (editMode && badgeHit(ix, iy, x, y)) return itemHit(h, Hit.BADGE, it, items, i, page, ix, iy);
                if (x >= ix - 8 * dp && x <= ix + icon + 8 * dp && y >= iy - 6 * dp && y <= iy + icon + labelSize * 1.5f)
                    return itemHit(h, Hit.ITEM, it, items, i, page, ix, iy);
            }
        }
        if (libIndex() - pagePos > 0.5f && inflate(pillRect, 8).contains(x, y)) { h.type = Hit.PILL; return h; }
        h.type = Hit.EMPTY;
        return h;
    }

    private Hit itemHit(Hit h, int type, Item it, List<Item> list, int i, int page, float ix, float iy) {
        h.type = type; h.item = it; h.list = list; h.index = i; h.page = page;
        h.app = it.folder ? null : store.get(it.key);
        h.rect.set(ix, iy, ix + icon, iy + icon);
        return h;
    }

    private boolean badgeHit(float ix, float iy, float x, float y) {
        return Math.hypot(x - (ix + 2 * dp), y - (iy + 2 * dp)) < 16 * dp;
    }

    private RectF inflate(RectF r, float by) {
        tmp2.set(r.left - by * dp, r.top - by * dp, r.right + by * dp, r.bottom + by * dp);
        return tmp2;
    }

    private Hit folderHit(float x, float y) {
        Hit h = new Hit();
        if (!folderPanel.contains(x, y)) {
            text.setTextSize(30 * sp);
            if (y > folderPanel.top - 70 * dp && y < folderPanel.top && x < folderPanel.right) h.type = Hit.FOLDER_TITLE;
            else h.type = Hit.FOLDER_OUT;
            return h;
        }
        for (int i = 0; i < folderKeys.size(); i++) {
            float ix = folderIconX(i), iy = folderIconY(i);
            if (x >= ix - 8 * dp && x <= ix + icon + 8 * dp && y >= iy - 6 * dp && y <= iy + icon + labelSize * 1.5f) {
                h.type = Hit.FOLDER_ITEM;
                h.index = i;
                h.app = store.get(folderKeys.get(i));
                h.rect.set(ix, iy, ix + icon, iy + icon);
                if (h.app == null) h.type = Hit.FOLDER_BG;
                return h;
            }
        }
        h.type = Hit.FOLDER_BG;
        return h;
    }

    private int menuRowAt(float x, float y) {
        if (!menuRect.contains(x, y)) return -1;
        float yy = menuRect.top;
        for (int i = 0; i < menuRows.size(); i++) {
            if (menuRows.get(i).gapBefore) yy += 8 * dp;
            if (y >= yy && y < yy + menuRowH) return i;
            yy += menuRowH;
        }
        return -1;
    }

    private int alertRowAt(float x, float y) {
        if (!alertRect.contains(x, y)) return -1;
        float by = alertButtonsTop();
        int i = (int) Math.floor((y - by) / (48 * dp));
        return i >= 0 && i < alertRows.size() ? i : -1;
    }

    // ================================================================== touch

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: onDown(x, y); break;
            case MotionEvent.ACTION_MOVE: onMove(x, y); break;
            case MotionEvent.ACTION_UP: onUp(x, y, false); break;
            case MotionEvent.ACTION_CANCEL: onUp(x, y, true); break;
            default:
        }
        invalidate();
        return true;
    }

    private void onDown(float x, float y) {
        downX = x; downY = y;
        downTime = SystemClock.uptimeMillis();
        notifFired = false;
        removeCallbacks(longPress);
        pressed = null;
        hit = null;
        if (alertVisible) { touch = T_ALERT; alertPressed = alertRowAt(x, y); return; }
        if (menuVisible) { touch = T_MENU; menuPressed = menuRowAt(x, y); return; }
        if (folderOpen) {
            touch = T_FOLDER;
            hit = folderHit(x, y);
            pressed = hit;
            baseScroll = folderScroll;
            if (hit.type == Hit.FOLDER_ITEM) postDelayed(longPress, 420);
            return;
        }
        if (pageAnimating) { pageAnimating = false; }
        if (!libScroller.isFinished()) libScroller.forceFinished(true);
        hit = hitTest(x, y);
        pressed = hit;
        touch = T_PENDING;
        basePos = pagePos;
        baseScroll = libScroll;
        if (hit.type == Hit.ITEM || hit.type == Hit.LIB || hit.type == Hit.WIDGET
                || (hit.type == Hit.EMPTY && pagePos < libIndex() - 0.5f)) {
            postDelayed(longPress, hit.type == Hit.EMPTY ? 600 : 420);
        }
    }

    private void onLongPress() {
        if (touch == T_FOLDER && hit != null && hit.type == Hit.FOLDER_ITEM) {
            openMenu(hit);
            touch = T_MENU_ARMED;
            return;
        }
        if (touch != T_PENDING || hit == null) return;
        if (editMode) return;
        if (hit.type == Hit.ITEM || hit.type == Hit.LIB) {
            openMenu(hit);
            touch = T_MENU_ARMED;
        } else if (hit.type == Hit.WIDGET || hit.type == Hit.EMPTY) {
            enterEdit();
            touch = T_IGNORE;
        }
    }

    private void onMove(float x, float y) {
        float dx = x - downX, dy = y - downY;
        float dist = (float) Math.hypot(dx, dy);
        switch (touch) {
            case T_PENDING:
                if (editMode && hit != null && hit.type == Hit.ITEM && dist > slop) {
                    removeCallbacks(longPress);
                    startDrag(hit, x, y);
                    touch = T_DRAG;
                } else if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                    removeCallbacks(longPress);
                    touch = T_PAGING;
                    pressed = null;
                    basePos = pagePos;
                    downX = x;
                } else if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                    removeCallbacks(longPress);
                    pressed = null;
                    if (Math.round(pagePos) >= libIndex()) { touch = T_LIB; downY = y; baseScroll = libScroll; }
                    else if (dy > 0 && !editMode) {
                        touch = downY < insetTop + 44 * dp ? T_NOTIF : T_SPOT;
                        downY = y;
                    } else touch = T_IGNORE;
                }
                break;
            case T_PAGING: {
                float pos = basePos - dx / W;
                float max = libIndex();
                if (pos < 0) pos = -rubber(-pos);
                else if (pos > max) pos = max + rubber(pos - max);
                pagePos = pos;
                dotsUntil = SystemClock.uptimeMillis() + 900;
                break;
            }
            case T_SPOT:
                spotProgress = Math.max(0, Math.min(1, dy / (H * 0.22f)));
                host.spotlightPull(spotProgress);
                break;
            case T_NOTIF:
                if (!notifFired && dy > 24 * dp) { notifFired = true; host.expandNotifications(); }
                break;
            case T_LIB:
                libScroll = Math.max(0, Math.min(libMaxScroll(), baseScroll - dy));
                break;
            case T_DRAG:
                dragX = x; dragY = y;
                updateHover();
                break;
            case T_MENU:
                menuPressed = menuRowAt(x, y);
                break;
            case T_MENU_ARMED:
                if (dist > 12 * dp) {
                    boolean draggable = hit != null && hit.type == Hit.ITEM;
                    if (draggable) {
                        menuVisible = false;
                        menuSpring.target = 0f;
                        enterEdit();
                        startDrag(hit, x, y);
                        touch = T_DRAG;
                    } else {
                        touch = T_MENU;
                        menuPressed = menuRowAt(x, y);
                    }
                }
                break;
            case T_FOLDER:
                if (Math.abs(dy) > slop && folderMaxScroll > 0) { removeCallbacks(longPress); touch = T_FOLDER_SCROLL; pressed = null; downY = y; baseScroll = folderScroll; }
                else if (dist > slop) { removeCallbacks(longPress); pressed = null; hit = null; }
                break;
            case T_FOLDER_SCROLL:
                folderScroll = Math.max(0, Math.min(folderMaxScroll, baseScroll - (y - downY)));
                break;
            case T_ALERT:
                alertPressed = alertRowAt(x, y);
                break;
            default:
        }
    }

    private float rubber(float over) { return 0.35f * (1 - 1 / (over * 1.2f + 1)); }

    private void onUp(float x, float y, boolean cancel) {
        removeCallbacks(longPress);
        vt.computeCurrentVelocity(1000);
        float vx = vt.getXVelocity(), vy = vt.getYVelocity();
        vt.recycle();
        vt = null;
        int t = touch;
        touch = T_NONE;
        Hit h = hit;
        pressed = null;
        switch (t) {
            case T_PENDING:
                if (!cancel && h != null) tap(h);
                break;
            case T_PAGING: {
                float target = Math.round(pagePos);
                if (Math.abs(vx) > 400 * dp) target = vx < 0 ? (float) Math.floor(pagePos) + 1 : (float) Math.ceil(pagePos) - 1;
                target = Math.max(0, Math.min(libIndex(), target));
                pageSpring.value = pagePos;
                pageSpring.velocity = -vx / W;
                pageSpring.target = target;
                pageAnimating = true;
                dotsUntil = SystemClock.uptimeMillis() + 900;
                break;
            }
            case T_SPOT:
                host.spotlightRelease(!cancel && (spotProgress > 0.35f || vy > 900 * dp));
                spotProgress = 0;
                break;
            case T_LIB:
                libScroller.fling(0, Math.round(libScroll), 0, Math.round(-vy), 0, 0, 0, Math.round(libMaxScroll()));
                break;
            case T_DRAG:
                drop();
                break;
            case T_MENU:
            case T_MENU_ARMED:
                if (menuPressed >= 0 && !cancel) {
                    Row r = menuRows.get(menuPressed);
                    closeMenu();
                    if (r.action != null) r.action.run();
                } else if (t == T_MENU && !menuRect.contains(x, y)) closeMenu();
                menuPressed = -1;
                break;
            case T_ALERT: {
                int i = alertRowAt(x, y);
                if (!cancel && i >= 0 && i == alertPressed) {
                    Row r = alertRows.get(i);
                    closeAlert();
                    if (r.action != null) r.action.run();
                }
                alertPressed = -1;
                break;
            }
            case T_FOLDER:
                if (cancel || h == null) break;
                if (h.type == Hit.FOLDER_ITEM && !editMode) launch(h.app, h.rect);
                else if (h.type == Hit.FOLDER_TITLE && editMode && !folderReadOnly && folder != null) rename(folder);
                else if (h.type == Hit.FOLDER_OUT || h.type == Hit.FOLDER_TITLE) closeFolder();
                break;
            default:
        }
        hit = null;
        // A tap that interrupted a page animation must still settle on a page.
        if (!pageAnimating && dragItem == null && Math.abs(pagePos - Math.round(pagePos)) > 0.001f)
            animateTo(Math.max(0, Math.min(libIndex(), Math.round(pagePos))));
        invalidate();
    }

    private void tap(Hit h) {
        switch (h.type) {
            case Hit.ITEM:
                if (h.item.folder) openFolder(h.item, new ArrayList<>(h.item.apps), h.item.title, false, h.rect);
                else if (!editMode && h.app != null) launch(h.app, h.rect);
                break;
            case Hit.BADGE:
                if (h.index == -2) {
                    prefs.put("widgets", false);
                    readPrefs();
                    loadLayout();
                } else confirmRemove(h.item, h.app);
                break;
            case Hit.DONE: exitEdit(); break;
            case Hit.EDIT: host.openSettings(); break;
            case Hit.PILL: host.openSpotlight(false); break;
            case Hit.LIB_FIELD: host.openSpotlight(true); break;
            case Hit.LIB: if (h.app != null) launch(h.app, h.rect); break;
            case Hit.LIB_LABEL: {
                Cat c = cats.get(h.index);
                ArrayList<String> keys = new ArrayList<>();
                for (AppInfo a : c.apps) keys.add(a.key);
                openFolder(null, keys, c.name, true, h.rect);
                break;
            }
            case Hit.WIDGET: if (!editMode) host.openWidgetApp(h.index); break;
            case Hit.EMPTY: if (editMode) exitEdit(); break;
            default:
        }
    }

    private void launch(AppInfo a, RectF r) {
        Rect from = new Rect();
        r.round(from);
        prefs.noteLaunch(a.key);
        host.launch(a, from);
        post(this::buildLibrary);
    }

    // ================================================================== drag & drop

    private void startDrag(Hit h, float x, float y) {
        dragItem = h.item;
        dragSource = h.list;
        dragSourceIndex = h.index;
        dragOffX = x - h.rect.left;
        dragOffY = y - h.rect.top;
        dragX = x; dragY = y;
        dragSource.remove(dragItem);
        dragLift.set(0);
        dragLift.target = 1f;
        mergeCandidate = null;
        mergeTarget = null;
        edgeDir = 0;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        updateHover();
    }

    private void updateHover() {
        float cx = dragX - dragOffX + icon / 2, cy = dragY - dragOffY + icon / 2;
        tmp.set(dockRect);
        tmp.inset(-6 * dp, -10 * dp);
        List<Item> list;
        hoverDock = tmp.contains(cx, cy);
        int page = Math.max(0, Math.min(libIndex() - 1, Math.round(pagePos)));
        float ox = (page - pagePos) * W;
        if (hoverDock) {
            list = lay.dock;
            hoverPage = -1;
        } else {
            list = lay.pages.get(page);
            hoverPage = page;
        }
        // Over the middle of another icon? That's a folder in the making.
        Item over = null;
        for (Item it : list) {
            float ix = (hoverDock ? it.x : ox + it.x) + icon / 2, iy = it.y + icon / 2;
            if (Math.hypot(cx - ix, cy - iy) < icon * 0.32f) { over = it; break; }
        }
        long now = SystemClock.uptimeMillis();
        if (over != null && !(dragItem.folder && !over.folder) && !(dragItem.folder && over.folder)) {
            if (over != mergeCandidate) { mergeCandidate = over; mergeSince = now; mergeTarget = null; }
            return;   // keep the current gap while hovering over an icon
        }
        mergeCandidate = null;
        mergeTarget = null;
        if (hoverDock) {
            int n = lay.dock.size();
            if (n >= Layout.DOCK_MAX) { hoverInsert = -1; return; }
            int best = 0; float bd = Float.MAX_VALUE;
            for (int i = 0; i <= n; i++) {
                float d = Math.abs(dockSlotX(i, n + 1) + icon / 2 - cx);
                if (d < bd) { bd = d; best = i; }
            }
            hoverInsert = best;
        } else {
            float lx = cx - ox;
            int col = (int) Math.floor((lx - side) / cellW);
            col = Math.max(0, Math.min(cols - 1, col));
            int row = (int) Math.floor((cy - gridTop) / rowH) - (page == 0 && showWidgets ? 2 : 0);
            row = Math.max(0, row);
            int slot = row * cols + col;
            hoverInsert = Math.max(0, Math.min(list.size(), slot));
        }
    }

    /** Per-frame drag work: folder dwell and edge paging. */
    private boolean dragTick() {
        long now = SystemClock.uptimeMillis();
        if (mergeCandidate != null && mergeTarget == null && now - mergeSince > 380) {
            mergeTarget = mergeCandidate;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        int dir = dragX < 22 * dp ? -1 : dragX > W - 22 * dp ? 1 : 0;
        if (dir != edgeDir) { edgeDir = dir; edgeSince = now; }
        if (dir != 0 && !pageAnimating && now - edgeSince > 550) {
            int page = Math.round(pagePos);
            int next = page + dir;
            if (next >= 0) {
                if (next >= libIndex()) {
                    // Past the last page: make a fresh one (unless the last one is already empty).
                    if (!lay.pages.get(libIndex() - 1).isEmpty()) lay.pages.add(new ArrayList<>());
                    else next = page;
                }
                if (next != page) {
                    animateTo(next);
                    dotsUntil = now + 900;
                }
            }
            edgeSince = now;
        }
        if (pageAnimating) updateHover();
        return true;
    }

    private void drop() {
        Item it = dragItem;
        if (it == null) return;
        int page = Math.max(0, Math.min(libIndex() - 1, Math.round(pagePos)));
        float ox = (page - pagePos) * W;
        if (mergeTarget != null) {
            Item target = mergeTarget;
            List<String> incoming = it.folder ? it.apps : Arrays.asList(it.key);
            if (target.folder) {
                target.apps.addAll(incoming);
            } else {
                Item f = Item.folder(folderNameFor(store.get(target.key), it.folder ? null : store.get(it.key)),
                        new ArrayList<>(Arrays.asList(target.key)));
                f.apps.addAll(incoming);
                f.x = target.x; f.y = target.y; f.placed = target.placed;
                List<Item> owner = lay.dock.contains(target) ? lay.dock : null;
                if (owner == null) for (List<Item> p : lay.pages) if (p.contains(target)) owner = p;
                if (owner != null) owner.set(owner.indexOf(target), f);
            }
        } else if (hoverDock && hoverInsert >= 0) {
            it.x = dragX - dragOffX; it.y = dragY - dragOffY; it.placed = true;
            lay.dock.add(Math.min(hoverInsert, lay.dock.size()), it);
        } else if (!hoverDock && hoverPage >= 0 && hoverInsert >= 0) {
            it.x = dragX - dragOffX - ox; it.y = dragY - dragOffY; it.placed = true;
            ArrayList<Item> pg = lay.pages.get(hoverPage);
            pg.add(Math.min(hoverInsert, pg.size()), it);
        } else {
            // Nowhere valid (e.g. full dock): put it back.
            it.placed = false;
            dragSource.add(Math.min(dragSourceIndex, dragSource.size()), it);
        }
        dragItem = null;
        mergeTarget = null;
        mergeCandidate = null;
        hoverInsert = -1;
        hoverPage = -1;
        hoverDock = false;
        lay.normalize(cap0, cap, false);
        save();
        invalidate();
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Current user of this profile (used by the spotlight). */
    public static boolean isMainUser(AppInfo a) { return a.user.equals(Process.myUserHandle()); }

    public Bitmap wallpaper() { return glass.wall; }
}
