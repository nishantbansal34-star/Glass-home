package com.nishant.glasshome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Spotlight search (swipe down on the Home Screen) and the App Library's A–Z search. */
public class SpotlightView extends FrameLayout {

    public interface Host {
        void launch(AppInfo a, Rect from);
        void webSearch(String q);
        void storeSearch(String q);
        void onSpotlightClosed();
    }

    private static final int E_HEADER = 0, E_APP = 1, E_TOP = 2, E_ROW = 3, E_CALC = 4, E_LETTER = 5;

    private static class Entry {
        int type; AppInfo app; String title, sub; Runnable action; final RectF r = new RectF(); int glyph;
    }

    private final Host host;
    private final Prefs prefs;
    private final AppStore store;
    private final Glass glass;
    private final EditText field;
    private final float dp, sp;
    private final Spring prog = new Spring(380f, 0.95f);
    private boolean open, library, dark;
    private int insetTop, W, H;
    private long lastFrame;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private float contentH, scroll;
    private final OverScroller scroller;
    private final RectF fieldRect = new RectF(), cancelRect = new RectF();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private float icon;
    private Entry pressed;
    private float downX, downY, baseScroll;
    private boolean scrolling;
    private VelocityTracker vt;
    private final int slop;

    public SpotlightView(Context c, Host host, Prefs prefs, AppStore store, Glass glass) {
        super(c);
        this.host = host;
        this.prefs = prefs;
        this.store = store;
        this.glass = glass;
        dp = getResources().getDisplayMetrics().density;
        sp = getResources().getDisplayMetrics().scaledDensity;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        scroller = new OverScroller(c);
        setWillNotDraw(false);
        setVisibility(GONE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);

        field = new EditText(c);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(0, 0, 0, 0);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) { scroll = 0; rebuild(); invalidate(); }
        });
        field.setOnEditorActionListener((v, id, ev) -> { runTop(); return true; });
        addView(field, new LayoutParams(1, 1));
    }

    public boolean isOpen() { return open || getVisibility() == VISIBLE; }

    public void setInsetTop(int t) { insetTop = t; requestLayout(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w; H = h;
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        W = r - l; H = b - t;
        text.setTextSize(17 * sp);
        text.setTypeface(Typeface.DEFAULT);
        float cw = text.measureText("Cancel") + 20 * dp;
        fieldRect.set(16 * dp, insetTop + 10 * dp, W - 16 * dp - cw, insetTop + 10 * dp + 44 * dp);
        cancelRect.set(fieldRect.right, fieldRect.top, W - 6 * dp, fieldRect.bottom);
        int left = Math.round(fieldRect.left + 40 * dp), right = Math.round(fieldRect.right - 12 * dp);
        field.measure(MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(fieldRect.height()), MeasureSpec.EXACTLY));
        field.layout(left, Math.round(fieldRect.top), right, Math.round(fieldRect.bottom));
        icon = Math.min(W * 0.155f, 64 * dp);
        rebuild();
    }

    // ------------------------------------------------------------------ open / close

    public void pull(float p, boolean libraryMode) {
        library = libraryMode;
        dark = prefs.isDark(getContext());
        styleField();
        if (getVisibility() != VISIBLE) { setVisibility(VISIBLE); rebuild(); }
        prog.set(p);
        field.setAlpha(p);
        field.setTranslationY((1 - p) * -20 * dp);
        invalidate();
    }

    public void open(boolean libraryMode) {
        library = libraryMode;
        dark = prefs.isDark(getContext());
        styleField();
        if (getVisibility() != VISIBLE) { setVisibility(VISIBLE); prog.set(0); }
        open = true;
        prog.target = 1f;
        scroll = 0;
        rebuild();
        field.requestFocus();
        postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && open) imm.showSoftInput(field, 0);
        }, 80);
        invalidate();
    }

    public void close() {
        if (getVisibility() != VISIBLE) return;
        open = false;
        prog.target = 0f;
        hideKeyboard();
        invalidate();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
    }

    private void styleField() {
        field.setTextColor(dark ? 0xFFFFFFFF : 0xFF000000);
        field.setHintTextColor(dark ? 0x99FFFFFF : 0x8A3C3C43);
        field.setHint(library ? "App Library" : "Search");
    }

    // ------------------------------------------------------------------ results

    private String query() { return field.getText().toString().trim(); }

    private void rebuild() {
        entries.clear();
        if (W == 0) return;
        String q = query();
        float y = fieldRect.bottom + 18 * dp;
        float side = 16 * dp;
        List<AppInfo> hits = match(q);
        if (library) {
            List<AppInfo> list = q.isEmpty() ? store.apps : hits;
            String last = null;
            for (AppInfo a : list) {
                String letter = a.label.isEmpty() ? "#" : a.label.substring(0, 1).toUpperCase(Locale.getDefault());
                if (!Character.isLetter(letter.charAt(0))) letter = "#";
                if (q.isEmpty() && !letter.equals(last)) {
                    Entry h = new Entry(); h.type = E_LETTER; h.title = letter;
                    h.r.set(side, y, W - side, y + 30 * dp); entries.add(h);
                    y += 30 * dp;
                    last = letter;
                }
                Entry e = new Entry(); e.type = E_ROW; e.app = a; e.title = a.label;
                e.r.set(side, y, W - side, y + 56 * dp);
                entries.add(e);
                y += 56 * dp;
            }
            contentH = y + 40 * dp;
            return;
        }
        if (q.isEmpty()) {
            y = header("Suggestions", y);
            ArrayList<AppInfo> sug = new ArrayList<>();
            for (String k : prefs.recents()) { AppInfo a = store.get(k); if (a != null && sug.size() < 8) sug.add(a); }
            for (AppInfo a : store.apps) { if (sug.size() >= 8) break; if (!sug.contains(a)) sug.add(a); }
            y = grid(sug, y);
            contentH = y;
            return;
        }
        Double v = Calc.eval(q);
        if (v != null && q.matches(".*\\d.*") && q.matches(".*[-+*/×÷x^%()].*")) {
            y = header("Calculator", y);
            Entry e = new Entry(); e.type = E_CALC; e.title = q; e.sub = "= " + Calc.format(v, true);
            e.r.set(side, y, W - side, y + 76 * dp); entries.add(e);
            y += 76 * dp + 14 * dp;
        }
        if (!hits.isEmpty()) {
            y = header("Top Hit", y);
            Entry top = new Entry(); top.type = E_TOP; top.app = hits.get(0); top.title = hits.get(0).label; top.sub = "Application";
            top.r.set(side, y, W - side, y + 72 * dp); entries.add(top);
            y += 72 * dp + 14 * dp;
            if (hits.size() > 1) {
                y = header("Apps", y);
                y = grid(hits.subList(1, Math.min(hits.size(), 9)), y);
            }
        }
        y = header("Search", y);
        final String qq = q;
        Entry web = new Entry(); web.type = E_ROW; web.title = "Search Web"; web.sub = "“" + q + "”"; web.glyph = 1;
        web.action = () -> host.webSearch(qq);
        web.r.set(side, y, W - side, y + 56 * dp); entries.add(web); y += 56 * dp;
        Entry ps = new Entry(); ps.type = E_ROW; ps.title = "Search Play Store"; ps.sub = "“" + q + "”"; ps.glyph = 2;
        ps.action = () -> host.storeSearch(qq);
        ps.r.set(side, y, W - side, y + 56 * dp); entries.add(ps); y += 56 * dp;
        contentH = y + 40 * dp;
    }

    private float header(String t, float y) {
        Entry h = new Entry(); h.type = E_HEADER; h.title = t;
        h.r.set(16 * dp, y, W - 16 * dp, y + 28 * dp);
        entries.add(h);
        return y + 28 * dp;
    }

    private float grid(List<AppInfo> apps, float y) {
        float side = 16 * dp;
        float cellW = (W - 2 * side) / 4f;
        float rowH = icon + 30 * dp;
        for (int i = 0; i < apps.size(); i++) {
            Entry e = new Entry(); e.type = E_APP; e.app = apps.get(i); e.title = apps.get(i).label;
            float x = side + (i % 4) * cellW + (cellW - icon) / 2f;
            float yy = y + (i / 4) * rowH;
            e.r.set(x, yy, x + icon, yy + icon);
            entries.add(e);
        }
        return y + ((apps.size() + 3) / 4) * rowH + 10 * dp;
    }

    private List<AppInfo> match(String q) {
        ArrayList<AppInfo> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        final String lq = q.toLowerCase(Locale.getDefault());
        final HashMap<AppInfo, Integer> score = new HashMap<>();
        List<String> rec = prefs.recents();
        for (AppInfo a : store.apps) {
            String l = a.label.toLowerCase(Locale.getDefault());
            int s = 0;
            if (l.startsWith(lq)) s = 300;
            else if (l.contains(" " + lq)) s = 200;
            else if (l.contains(lq)) s = 100;
            else if (acronym(l).startsWith(lq)) s = 90;
            if (s == 0) continue;
            int r = rec.indexOf(a.key);
            if (r >= 0) s += 40 - r;
            score.put(a, s);
            out.add(a);
        }
        out.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
        return out;
    }

    private static String acronym(String l) {
        StringBuilder b = new StringBuilder();
        for (String w : l.split("\\s+")) if (!w.isEmpty()) b.append(w.charAt(0));
        return b.toString();
    }

    private void runTop() {
        for (Entry e : entries) {
            if (e.type == E_TOP || e.type == E_APP || (e.type == E_ROW && (e.app != null || e.action != null))) { activate(e); return; }
        }
    }

    private void activate(Entry e) {
        if (e.app != null) {
            Rect r = new Rect();
            e.r.round(r);
            r.offset(0, Math.round(-scroll));
            prefs.noteLaunch(e.app.key);
            host.launch(e.app, r);
            post(this::finishNow);
        } else if (e.action != null) {
            e.action.run();
            post(this::finishNow);
        }
    }

    /** Close instantly (used after launching something). */
    private void finishNow() {
        open = false;
        hideKeyboard();
        prog.set(0);
        field.setText("");
        setVisibility(GONE);
        host.onSpotlightClosed();
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean anim = prog.step(dt);
        if (!scroller.isFinished() && scroller.computeScrollOffset()) { scroll = scroller.getCurrY(); anim = true; }
        float p = Math.max(0, Math.min(1, prog.value));
        field.setAlpha(p);
        field.setTranslationY((1 - p) * -20 * dp);
        if (!open && prog.target == 0 && p <= 0.001f && !anim) {
            field.setText("");
            setVisibility(GONE);
            lastFrame = 0;
            host.onSpotlightClosed();
            return;
        }
        glass.drawBackdrop(c, p);
        c.drawColor(Icons.alpha(dark ? 0x000000 : 0xFFFFFF, (dark ? 0.2f : 0.25f) * p));

        int fg = dark ? 0xFFFFFF : 0x000000;
        // Results
        c.save();
        c.clipRect(0, fieldRect.bottom + 6 * dp, W, H);
        c.translate(0, -scroll + (1 - p) * -30 * dp);
        int style = prefs.iconStyle(), tint = prefs.tint();
        for (Entry e : entries) {
            if (e.r.bottom - scroll < 0 || e.r.top - scroll > H) continue;
            boolean pr = e == pressed;
            switch (e.type) {
                case E_HEADER:
                    text.setTypeface(bold); text.setTextSize(15 * sp); text.setTextAlign(Paint.Align.LEFT);
                    text.setColor(Icons.alpha(fg, 0.55f * p));
                    c.drawText(e.title, e.r.left + 4 * dp, e.r.bottom - 8 * dp, text);
                    break;
                case E_LETTER:
                    text.setTypeface(bold); text.setTextSize(15 * sp); text.setTextAlign(Paint.Align.LEFT);
                    text.setColor(Icons.alpha(fg, 0.5f * p));
                    c.drawText(e.title, e.r.left + 4 * dp, e.r.bottom - 7 * dp, text);
                    break;
                case E_APP: {
                    store.icons.draw(c, e.app, e.r.left, e.r.top, e.r.width(), style, tint, glass, pr, p);
                    text.setTypeface(medium); text.setTextSize(12 * sp); text.setTextAlign(Paint.Align.CENTER);
                    text.setColor(Icons.alpha(fg, 0.9f * p));
                    String t = TextUtils.ellipsize(e.title, text, e.r.width() * 1.3f, TextUtils.TruncateAt.END).toString();
                    c.drawText(t, e.r.centerX(), e.r.bottom + 6 * dp - text.ascent(), text);
                    break;
                }
                case E_TOP: {
                    glass.panel(c, e.r, 22 * dp, p, 0.25f, 0);
                    if (pr) { fill.setColor(Icons.alpha(fg, 0.08f)); c.drawRoundRect(e.r, 22 * dp, 22 * dp, fill); }
                    float s = 48 * dp;
                    store.icons.draw(c, e.app, e.r.left + 12 * dp, e.r.centerY() - s / 2, s, style, tint, glass, false, p);
                    text.setTypeface(medium); text.setTextSize(17 * sp); text.setTextAlign(Paint.Align.LEFT);
                    text.setColor(Icons.alpha(fg, p));
                    c.drawText(TextUtils.ellipsize(e.title, text, e.r.width() - 90 * dp, TextUtils.TruncateAt.END).toString(),
                            e.r.left + 74 * dp, e.r.centerY() - 3 * dp, text);
                    text.setTypeface(Typeface.DEFAULT); text.setTextSize(13 * sp);
                    text.setColor(Icons.alpha(fg, 0.55f * p));
                    c.drawText(e.sub, e.r.left + 74 * dp, e.r.centerY() + 16 * dp, text);
                    break;
                }
                case E_CALC: {
                    glass.panel(c, e.r, 22 * dp, p, 0.25f, 0);
                    text.setTypeface(Typeface.DEFAULT); text.setTextSize(14 * sp); text.setTextAlign(Paint.Align.LEFT);
                    text.setColor(Icons.alpha(fg, 0.55f * p));
                    c.drawText(e.title, e.r.left + 18 * dp, e.r.top + 26 * dp, text);
                    text.setTypeface(medium); text.setTextSize(26 * sp);
                    text.setColor(Icons.alpha(fg, p));
                    c.drawText(e.sub, e.r.left + 18 * dp, e.r.bottom - 16 * dp, text);
                    break;
                }
                case E_ROW: {
                    if (pr) { fill.setColor(Icons.alpha(fg, 0.08f)); c.drawRoundRect(e.r, 14 * dp, 14 * dp, fill); }
                    float s = 40 * dp;
                    float ix = e.r.left + 8 * dp, iy = e.r.centerY() - s / 2;
                    if (e.app != null) store.icons.draw(c, e.app, ix, iy, s, style, tint, glass, false, p);
                    else {
                        RectF gr = new RectF(ix, iy, ix + s, iy + s);
                        glass.panel(c, gr, s * 0.25f, p, 0.3f, 0);
                        stroke.setColor(Icons.alpha(e.glyph == 1 ? (dark ? 0x0A84FF : 0x007AFF) : (dark ? 0x30D158 : 0x248A3D), p));
                        stroke.setStrokeWidth(2 * dp);
                        if (e.glyph == 1) {
                            c.drawCircle(gr.centerX() - 2 * dp, gr.centerY() - 2 * dp, 8 * dp, stroke);
                            c.drawLine(gr.centerX() + 4 * dp, gr.centerY() + 4 * dp, gr.centerX() + 10 * dp, gr.centerY() + 10 * dp, stroke);
                        } else {
                            android.graphics.Path tri = new android.graphics.Path();
                            tri.moveTo(gr.centerX() - 7 * dp, gr.centerY() - 10 * dp);
                            tri.lineTo(gr.centerX() + 10 * dp, gr.centerY());
                            tri.lineTo(gr.centerX() - 7 * dp, gr.centerY() + 10 * dp);
                            tri.close();
                            c.drawPath(tri, stroke);
                        }
                    }
                    text.setTypeface(Typeface.DEFAULT); text.setTextSize(17 * sp); text.setTextAlign(Paint.Align.LEFT);
                    text.setColor(Icons.alpha(fg, p));
                    float tx = ix + s + 14 * dp;
                    if (e.sub != null) {
                        c.drawText(e.title, tx, e.r.centerY() - 3 * dp, text);
                        text.setTextSize(13 * sp);
                        text.setColor(Icons.alpha(fg, 0.55f * p));
                        c.drawText(TextUtils.ellipsize(e.sub, text, e.r.right - tx - 8 * dp, TextUtils.TruncateAt.END).toString(), tx, e.r.centerY() + 15 * dp, text);
                    } else {
                        c.drawText(TextUtils.ellipsize(e.title, text, e.r.right - tx - 8 * dp, TextUtils.TruncateAt.END).toString(),
                                tx, e.r.centerY() - (text.ascent() + text.descent()) / 2, text);
                    }
                    fill.setColor(Icons.alpha(fg, 0.1f * p));
                    c.drawRect(tx, e.r.bottom - 0.6f * dp, e.r.right, e.r.bottom, fill);
                    break;
                }
                default:
            }
        }
        c.restore();

        // Search field + Cancel
        c.save();
        c.translate(0, (1 - p) * -20 * dp);
        glass.panel(c, fieldRect, fieldRect.height() / 2, p, 0.2f, 0);
        stroke.setColor(Icons.alpha(fg, 0.55f * p));
        stroke.setStrokeWidth(1.9f * dp);
        float mx = fieldRect.left + 22 * dp, my = fieldRect.centerY();
        c.drawCircle(mx - 1.5f * dp, my - 1.5f * dp, 6.5f * dp, stroke);
        c.drawLine(mx + 3.5f * dp, my + 3.5f * dp, mx + 8 * dp, my + 8 * dp, stroke);
        text.setTypeface(Typeface.DEFAULT); text.setTextSize(17 * sp); text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Icons.alpha(dark ? 0x0A84FF : 0x007AFF, p));
        c.drawText("Cancel", cancelRect.centerX(), cancelRect.centerY() - (text.ascent() + text.descent()) / 2, text);
        c.restore();

        if (anim) postInvalidateOnAnimation(); else lastFrame = 0;
    }

    // ------------------------------------------------------------------ touch

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        // Let the EditText get taps; everything else is ours.
        return !(e.getY() >= fieldRect.top && e.getY() <= fieldRect.bottom && e.getX() >= fieldRect.left && e.getX() <= fieldRect.right);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; baseScroll = scroll; scrolling = false;
                scroller.forceFinished(true);
                pressed = entryAt(x, y);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scrolling && Math.abs(y - downY) > slop) { scrolling = true; pressed = null; hideKeyboard(); }
                if (scrolling) {
                    float max = Math.max(0, contentH - H + 40 * dp);
                    scroll = Math.max(0, Math.min(max, baseScroll - (y - downY)));
                    // Pulling down from the top closes, like iOS.
                    if (baseScroll - (y - downY) < -110 * dp) { close(); scrolling = false; }
                }
                break;
            case MotionEvent.ACTION_UP:
                vt.computeCurrentVelocity(1000);
                if (scrolling) {
                    float max = Math.max(0, contentH - H + 40 * dp);
                    scroller.fling(0, Math.round(scroll), 0, Math.round(-vt.getYVelocity()), 0, 0, 0, Math.round(max));
                } else {
                    Entry hit = entryAt(x, y);
                    if (cancelRect.contains(x, y)) close();
                    else if (hit != null && hit == pressed) activate(hit);
                    else if (hit == null && y > fieldRect.bottom) close();
                }
                pressed = null;
                vt.recycle(); vt = null;
                break;
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                if (vt != null) { vt.recycle(); vt = null; }
                break;
            default:
        }
        invalidate();
        return true;
    }

    private Entry entryAt(float x, float y) {
        if (y < fieldRect.bottom) return null;
        float yy = y + scroll;
        for (Entry e : entries) {
            if (e.type == E_HEADER || e.type == E_LETTER || e.type == E_CALC) continue;
            float extra = e.type == E_APP ? 24 * dp : 0;
            if (x >= e.r.left - 6 * dp && x <= e.r.right + 6 * dp && yy >= e.r.top && yy <= e.r.bottom + extra) return e;
        }
        return null;
    }
}
