package com.nishant.glasshome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.function.Consumer;

public class HomeActivity extends Activity implements HomeView.Host, SpotlightView.Host, AppStore.Listener {

    private Prefs prefs;
    private AppStore store;
    private HomeView home;
    private SpotlightView spot;
    private int seenVersion = -1;
    private boolean resumed, wasResumedAtNewIntent, launchedApp;
    private int wallW, wallH, wallMode = -1, wallStyle = -1;
    private boolean wallDark;
    private long wallStamp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        if (Build.VERSION.SDK_INT >= 29) { w.setStatusBarContrastEnforced(false); w.setNavigationBarContrastEnforced(false); }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        else w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        prefs = new Prefs(this);
        store = new AppStore(this, new Icons());
        store.setListener(this);
        home = new HomeView(this, this, prefs, store);
        spot = new SpotlightView(this, this, prefs, store, home.glass);

        FrameLayout root = new FrameLayout(this);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        root.addView(spot, new FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = s.top; bottom = s.bottom;
            } else {
                top = ins.getSystemWindowInsetTop(); bottom = ins.getSystemWindowInsetBottom();
            }
            home.setInsets(top, bottom);
            spot.setInsetTop(top);
            return ins;
        });
        setContentView(root);
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (r - l != wallW || bt - t != wallH) { wallW = r - l; wallH = bt - t; loadWallpaper(true); }
        });
        store.reload();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (prefs.version() != seenVersion) {
            seenVersion = prefs.version();
            home.readPrefs();
            loadWallpaper(false);
            if (!store.apps.isEmpty()) home.loadLayout();
        }
        home.buildLibrary();
        if (launchedApp) { launchedApp = false; home.playReturn(); }
        home.invalidate();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pressing Home while already on the Home Screen: close things, then go to page 1.
        boolean alreadyHome = resumed || hasWindowFocus();
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            if (spot.isOpen()) spot.close();
            else if (alreadyHome) home.goHome(true);
            else home.goHome(false);
        }
    }

    @Override public void onBackPressed() {
        if (spot.isOpen()) { spot.close(); return; }
        home.goHome(false);
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        home.readPrefs();
        loadWallpaper(false);
        store.renderIcons(store.icons.size());
        home.invalidate();
    }

    // ------------------------------------------------------------------ wallpaper

    private void loadWallpaper(boolean force) {
        if (wallW <= 0 || wallH <= 0) return;
        boolean dark = prefs.isDark(this);
        long stamp = Wallpapers.photoFile(this).lastModified();
        if (!force && prefs.wallMode() == wallMode && prefs.wallStyle() == wallStyle && dark == wallDark && stamp == wallStamp) return;
        wallMode = prefs.wallMode(); wallStyle = prefs.wallStyle(); wallDark = dark; wallStamp = stamp;
        Bitmap bmp = Wallpapers.load(this, prefs, wallW, wallH, dark);
        home.glass.setWallpaper(bmp);
        boolean lightTop = home.glass.topLuminance() > 0.62f;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) ic.setSystemBarsAppearance(lightTop ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        } else {
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility();
            d.setSystemUiVisibility(lightTop ? f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        home.contentChanged();
    }

    // ------------------------------------------------------------------ AppStore.Listener

    @Override public void onAppsChanged() {
        home.loadLayout();
    }

    @Override public void onIconsReady() {
        home.contentChanged();
        if (spot.getVisibility() == View.VISIBLE) spot.invalidate();
    }

    // ------------------------------------------------------------------ HomeView.Host

    @Override public void launch(AppInfo a, Rect from) {
        try {
            Bundle opts = ActivityOptions.makeScaleUpAnimation(home, from.left, from.top, from.width(), from.height()).toBundle();
            store.launcherApps().startMainActivity(a.cn, a.user, from, opts);
            launchedApp = true;
        } catch (Exception e) {
            Toast.makeText(this, "Can’t open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void openSpotlight(boolean library) { spot.open(library); }

    @Override public void spotlightPull(float p) { spot.pull(p, false); }

    @Override public void spotlightRelease(boolean open) {
        if (open) spot.open(false); else spot.close();
    }

    @Override public void onSpotlightClosed() { home.invalidate(); }

    @Override public void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Throwable ignored) { }
    }

    @Override public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override public void appDetails(AppInfo a, Rect from) {
        try { store.launcherApps().startAppDetailsActivity(a.cn, a.user, from, null); } catch (Exception ignored) { }
    }

    @Override public void uninstall(AppInfo a) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", a.pkg(), null));
            i.putExtra(Intent.EXTRA_USER, a.user);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "This app can’t be deleted", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void startShortcut(ShortcutInfo s, Rect from) {
        try { store.launcherApps().startShortcut(s, from, null); } catch (Exception ignored) { }
    }

    @Override public void openWidgetApp(int kind) {
        Intent i;
        if (kind == Widgets.CLOCK) i = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
        else i = new Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                .appendPath(String.valueOf(System.currentTimeMillis())).build());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (ActivityNotFoundException ignored) { }
    }

    @Override public void promptText(String title, String current, Consumer<String> done) {
        final EditText et = new EditText(this);
        et.setText(current);
        et.setSingleLine(true);
        et.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et);
        int theme = home.isDarkTheme() ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        new AlertDialog.Builder(this, theme)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("Done", (d, w) -> done.accept(et.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
        et.requestFocus();
    }

    // ------------------------------------------------------------------ SpotlightView.Host

    @Override public void webSearch(String q) {
        try {
            Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
            i.putExtra(SearchManager.QUERY, q);
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))); }
            catch (Exception ignored) { }
        }
    }

    @Override public void storeSearch(String q) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q)))); }
        catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(q)))); }
            catch (Exception ignored) { }
        }
    }
}
