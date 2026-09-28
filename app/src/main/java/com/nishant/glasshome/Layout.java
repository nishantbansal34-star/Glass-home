package com.nishant.glasshome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** What sits where: Home Screen pages (ordered, iOS-style flow), and the dock. */
public class Layout {
    public static class Item {
        public boolean folder;
        public String key;                              // app key (apps only)
        public String title = "Folder";                 // folders only
        public ArrayList<String> apps = new ArrayList<>(); // folders only
        // animated on-screen position (top-left of the icon, relative to its page)
        public float x, y;
        public boolean placed;
        public final float phase = (float) (Math.random() * Math.PI * 2);

        public static Item app(String k) { Item i = new Item(); i.key = k; return i; }
        public static Item folder(String title, List<String> keys) {
            Item i = new Item(); i.folder = true; i.title = title; i.apps.addAll(keys); return i;
        }
    }

    public final ArrayList<ArrayList<Item>> pages = new ArrayList<>();
    public final ArrayList<Item> dock = new ArrayList<>();
    public static final int DOCK_MAX = 4;

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            JSONArray ps = new JSONArray();
            for (List<Item> p : pages) ps.put(arr(p));
            o.put("pages", ps);
            o.put("dock", arr(dock));
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONArray arr(List<Item> l) throws Exception {
        JSONArray a = new JSONArray();
        for (Item i : l) {
            JSONObject o = new JSONObject();
            if (i.folder) {
                o.put("f", i.title);
                JSONArray k = new JSONArray();
                for (String s : i.apps) k.put(s);
                o.put("apps", k);
            } else o.put("a", i.key);
            a.put(o);
        }
        return a;
    }

    private static ArrayList<Item> read(JSONArray a) throws Exception {
        ArrayList<Item> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            if (o.has("f")) {
                ArrayList<String> keys = new ArrayList<>();
                JSONArray k = o.getJSONArray("apps");
                for (int j = 0; j < k.length(); j++) keys.add(k.getString(j));
                out.add(Item.folder(o.getString("f"), keys));
            } else out.add(Item.app(o.getString("a")));
        }
        return out;
    }

    /**
     * Rebuild from saved JSON, drop apps that are gone, and place newly installed apps.
     * Returns true if anything changed (so the caller saves).
     */
    public boolean load(String json, AppStore store, Prefs prefs, int cap0, int cap) {
        pages.clear();
        dock.clear();
        boolean changed = false;
        if (json != null) {
            try {
                JSONObject o = new JSONObject(json);
                JSONArray ps = o.getJSONArray("pages");
                for (int i = 0; i < ps.length(); i++) pages.add(read(ps.getJSONArray(i)));
                dock.addAll(read(o.getJSONArray("dock")));
            } catch (Exception e) {
                pages.clear();
                dock.clear();
                json = null;
            }
        }
        if (json == null) {
            firstRun(store, cap0, cap);
            return true;
        }
        // Remove missing apps.
        changed |= prune(dock, store);
        for (List<Item> p : pages) changed |= prune(p, store);
        // Add new apps.
        HashSet<String> placed = new HashSet<>();
        collect(dock, placed);
        for (List<Item> p : pages) collect(p, placed);
        List<String> removed = prefs.removed();
        HashSet<String> removedSet = new HashSet<>(removed);
        boolean removedChanged = removed.retainAll(store.byKey.keySet());
        if (removedChanged) prefs.setRemoved(removed);
        for (AppInfo a : store.apps) {
            if (placed.contains(a.key) || removedSet.contains(a.key)) continue;
            if (prefs.newToHome()) {
                append(Item.app(a.key), cap0, cap);
            } else {
                removed.add(a.key);
                prefs.setRemoved(removed);
            }
            changed = true;
        }
        if (pages.isEmpty()) { pages.add(new ArrayList<>()); changed = true; }
        return changed;
    }

    private static void collect(List<Item> l, HashSet<String> out) {
        for (Item i : l) {
            if (i.folder) out.addAll(i.apps); else out.add(i.key);
        }
    }

    private static boolean prune(List<Item> l, AppStore store) {
        boolean changed = false;
        for (int i = l.size() - 1; i >= 0; i--) {
            Item it = l.get(i);
            if (it.folder) {
                changed |= it.apps.retainAll(store.byKey.keySet());
                if (it.apps.isEmpty()) { l.remove(i); changed = true; }
                else if (it.apps.size() == 1) { l.set(i, Item.app(it.apps.get(0))); changed = true; }
            } else if (store.get(it.key) == null) {
                l.remove(i);
                changed = true;
            }
        }
        return changed;
    }

    public void append(Item it, int cap0, int cap) {
        for (int p = 0; p < pages.size(); p++) {
            if (pages.get(p).size() < (p == 0 ? cap0 : cap)) { pages.get(p).add(it); return; }
        }
        ArrayList<Item> np = new ArrayList<>();
        np.add(it);
        pages.add(np);
    }

    private static final String[] FIRST = {
            "calendar", "clock", "photos", "gallery", "camera", "gmail", "mail", "maps", "weather",
            "notes", "keep", "reminders", "files", "calculator", "play store", "youtube", "settings",
            "whatsapp", "instagram", "contacts", "chrome", "spotify", "music"};

    private void firstRun(AppStore store, int cap0, int cap) {
        List<AppInfo> dockApps = store.defaultDock();
        HashSet<String> used = new HashSet<>();
        for (AppInfo a : dockApps) {
            if (dock.size() >= DOCK_MAX) break;
            dock.add(Item.app(a.key));
            used.add(a.key);
        }
        ArrayList<AppInfo> ordered = new ArrayList<>();
        for (String w : FIRST) {
            for (AppInfo a : store.apps) {
                if (used.contains(a.key) || ordered.contains(a)) continue;
                if (a.label.toLowerCase(Locale.ROOT).equals(w)
                        || (a.label.toLowerCase(Locale.ROOT).startsWith(w) && a.label.length() < w.length() + 3)) {
                    ordered.add(a);
                    break;
                }
            }
        }
        for (AppInfo a : store.apps) if (!used.contains(a.key) && !ordered.contains(a)) ordered.add(a);
        pages.add(new ArrayList<>());
        for (AppInfo a : ordered) append(Item.app(a.key), cap0, cap);
    }

    /** Push overflowing items onto the next page (iOS behaviour), and drop empty trailing pages. */
    public void normalize(int cap0, int cap, boolean dropEmpty) {
        for (int p = 0; p < pages.size(); p++) {
            int c = p == 0 ? cap0 : cap;
            ArrayList<Item> page = pages.get(p);
            while (page.size() > c) {
                Item last = page.remove(page.size() - 1);
                if (p + 1 >= pages.size()) pages.add(new ArrayList<>());
                pages.get(p + 1).add(0, last);
                last.placed = false;
            }
        }
        if (dropEmpty) {
            for (int p = pages.size() - 1; p >= 1; p--) if (pages.get(p).isEmpty()) pages.remove(p);
        }
        if (pages.isEmpty()) pages.add(new ArrayList<>());
    }

    public boolean contains(String key) {
        HashSet<String> s = new HashSet<>();
        collect(dock, s);
        for (List<Item> p : pages) collect(p, s);
        return s.contains(key);
    }

    /** Remove an app from wherever it is on the Home Screen. */
    public void removeKey(String key) {
        removeFrom(dock, key);
        for (List<Item> p : pages) removeFrom(p, key);
    }

    private static void removeFrom(List<Item> l, String key) {
        for (int i = l.size() - 1; i >= 0; i--) {
            Item it = l.get(i);
            if (it.folder) {
                it.apps.remove(key);
                if (it.apps.isEmpty()) l.remove(i);
                else if (it.apps.size() == 1) l.set(i, Item.app(it.apps.get(0)));
            } else if (key.equals(it.key)) l.remove(i);
        }
    }
}
