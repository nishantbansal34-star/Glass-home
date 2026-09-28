package com.nishant.glasshome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** All user settings in one place. Every write bumps a version so the home screen knows to refresh. */
public class Prefs {
    public static final int ICON_DEFAULT = 0, ICON_DARK = 1, ICON_CLEAR = 2, ICON_TINTED = 3;
    public static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;
    public static final int WALL_BUILTIN = 0, WALL_PHOTO = 1, WALL_SYSTEM = 2;
    public static final int[] TINTS = {0xFFFFB340, 0xFF64D2FF, 0xFFFF6482, 0xFF30D158, 0xFFBF5AF2, 0xFFFFFFFF};

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    public int iconStyle() { return sp.getInt("iconStyle", ICON_DEFAULT); }
    public int tint() { return sp.getInt("tint", TINTS[0]); }
    /** 0 = ultra clear, 100 = fully tinted (iOS 27 Liquid Glass slider). */
    public int glass() { return sp.getInt("glass", 35); }
    public boolean labels() { return sp.getBoolean("labels", true); }
    public int theme() { return sp.getInt("theme", THEME_SYSTEM); }
    public int cols() { return sp.getInt("cols", 4); }
    public int rows() { return sp.getInt("rows", 6); }
    public boolean widgets() { return sp.getBoolean("widgets", true); }
    public boolean newToHome() { return sp.getBoolean("newToHome", true); }
    public int wallMode() { return sp.getInt("wallMode", WALL_BUILTIN); }
    public int wallStyle() { return sp.getInt("wallStyle", 0); }
    public int version() { return sp.getInt("version", 0); }
    public String layout() { return sp.getString("layout", null); }
    public boolean largeIcons() { return sp.getBoolean("large", false); }

    public boolean isDark(Context c) {
        int t = theme();
        if (t == THEME_LIGHT) return false;
        if (t == THEME_DARK) return true;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public void put(String k, int v) { sp.edit().putInt(k, v).putInt("version", version() + 1).apply(); }
    public void put(String k, boolean v) { sp.edit().putBoolean(k, v).putInt("version", version() + 1).apply(); }
    /** Layout saves don't bump the version (the home screen already has it). */
    public void saveLayout(String json) { sp.edit().putString("layout", json).apply(); }
    public void resetLayout() { sp.edit().remove("layout").remove("removed").putInt("version", version() + 1).apply(); }

    // ---- apps the user took off the Home Screen (they stay in the App Library)
    public List<String> removed() { return list("removed"); }
    public void setRemoved(List<String> l) { putList("removed", l); }

    // ---- recently opened apps, newest first (feeds Suggestions)
    public List<String> recents() { return list("recents"); }
    public void noteLaunch(String key) {
        List<String> l = recents();
        l.remove(key);
        l.add(0, key);
        while (l.size() > 12) l.remove(l.size() - 1);
        putList("recents", l);
    }

    private List<String> list(String k) {
        ArrayList<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(k, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    private void putList(String k, List<String> l) {
        JSONArray a = new JSONArray();
        for (String s : l) a.put(s);
        sp.edit().putString(k, a.toString()).apply();
    }
}
