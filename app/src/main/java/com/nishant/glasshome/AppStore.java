package com.nishant.glasshome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Knows every launchable app and renders icons off the main thread. */
public class AppStore {
    public interface Listener { void onAppsChanged(); void onIconsReady(); }

    private final Context ctx;
    private final LauncherApps la;
    private final UserManager um;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    public final Icons icons;
    public List<AppInfo> apps = new ArrayList<>();
    public HashMap<String, AppInfo> byKey = new HashMap<>();
    private Listener listener;
    private int generation;

    public AppStore(Context c, Icons icons) {
        ctx = c.getApplicationContext();
        this.icons = icons;
        la = (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
        la.registerCallback(new LauncherApps.Callback() {
            @Override public void onPackageRemoved(String p, UserHandle u) { reload(); }
            @Override public void onPackageAdded(String p, UserHandle u) { reload(); }
            @Override public void onPackageChanged(String p, UserHandle u) { reload(); }
            @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { reload(); }
            @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { reload(); }
        }, main);
    }

    public void setListener(Listener l) { listener = l; }
    public LauncherApps launcherApps() { return la; }

    public String keyOf(LauncherActivityInfo i) {
        String k = i.getComponentName().flattenToShortString();
        if (!i.getUser().equals(Process.myUserHandle())) k += "#" + um.getSerialNumberForUser(i.getUser());
        return k;
    }

    public void reload() {
        ArrayList<AppInfo> list = new ArrayList<>();
        HashMap<String, AppInfo> map = new HashMap<>();
        List<UserHandle> users;
        try { users = um.getUserProfiles(); } catch (Exception e) { users = Collections.singletonList(Process.myUserHandle()); }
        for (UserHandle u : users) {
            List<LauncherActivityInfo> acts;
            try { acts = la.getActivityList(null, u); } catch (Exception e) { continue; }
            for (LauncherActivityInfo i : acts) {
                String k = keyOf(i);
                if (map.containsKey(k)) continue;
                AppInfo old = byKey.get(k);
                AppInfo a = new AppInfo(i, k);
                if (old != null) { a.raw = old.raw; a.full = old.full; a.dark = old.dark; a.glyph = old.glyph; a.renderedSize = old.renderedSize; }
                list.add(a);
                map.put(k, a);
            }
        }
        final Collator col = Collator.getInstance(Locale.getDefault());
        Collections.sort(list, (a, b) -> col.compare(a.label, b.label));
        apps = list;
        byKey = map;
        if (listener != null) listener.onAppsChanged();
        renderIcons(icons.size());
    }

    /** Render (or re-render) every icon at the given pixel size in the background. */
    public void renderIcons(final int size) {
        if (size <= 0) return;
        final int gen = ++generation;
        final List<AppInfo> snapshot = new ArrayList<>(apps);
        worker.execute(() -> {
            int n = 0;
            for (AppInfo a : snapshot) {
                if (gen != generation) return;
                if (a.renderedSize == size && a.full != null) continue;
                try {
                    if (a.raw == null) a.raw = a.lai.getIcon(ctx.getResources().getDisplayMetrics().densityDpi);
                    icons.render(a, size);
                } catch (Throwable ignored) { }
                if (++n % 8 == 0) main.post(() -> { if (listener != null) listener.onIconsReady(); });
            }
            main.post(() -> { if (listener != null) listener.onIconsReady(); });
        });
    }

    public AppInfo get(String key) { return byKey.get(key); }

    /** Finds the app that would handle an intent (to fill the dock on first run). */
    public AppInfo resolve(Intent i) {
        try {
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(i, 0);
            String pkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
            if (pkg == null || pkg.equals("android")) {
                List<ResolveInfo> all = ctx.getPackageManager().queryIntentActivities(i, 0);
                if (!all.isEmpty()) pkg = all.get(0).activityInfo.packageName;
            }
            if (pkg == null) return null;
            for (AppInfo a : apps) if (a.pkg().equals(pkg) && a.user.equals(Process.myUserHandle())) return a;
        } catch (Exception ignored) { }
        return null;
    }

    public List<AppInfo> defaultDock() {
        ArrayList<AppInfo> out = new ArrayList<>();
        Intent[] intents = {
                new Intent(Intent.ACTION_DIAL),
                new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        };
        for (Intent i : intents) {
            AppInfo a = resolve(i);
            if (a != null && !out.contains(a)) out.add(a);
        }
        return out;
    }
}
