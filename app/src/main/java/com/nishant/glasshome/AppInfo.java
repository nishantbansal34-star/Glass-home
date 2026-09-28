package com.nishant.glasshome;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

/** One launchable app, plus its pre-rendered icon bitmaps. */
public class AppInfo {
    public final String key;
    public final String label;
    public final ComponentName cn;
    public final UserHandle user;
    public final LauncherActivityInfo lai;
    public final String category;
    public final long installed;
    public final boolean system;

    public volatile Drawable raw;      // original drawable from the system
    public volatile Bitmap full;       // default iOS-style icon (squircle, full colour)
    public volatile Bitmap dark;       // dark-mode icon
    public volatile Bitmap glyph;      // light glyph for Clear / Tinted icons
    public volatile int renderedSize;

    public AppInfo(LauncherActivityInfo lai, String key) {
        this.lai = lai;
        this.key = key;
        this.cn = lai.getComponentName();
        this.user = lai.getUser();
        CharSequence l = lai.getLabel();
        this.label = l == null ? cn.getPackageName() : l.toString().trim();
        this.installed = lai.getFirstInstallTime();
        ApplicationInfo ai = lai.getApplicationInfo();
        this.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        this.category = categoryOf(ai, system);
    }

    public String pkg() { return cn.getPackageName(); }

    private static String categoryOf(ApplicationInfo ai, boolean system) {
        if ((ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) return "Games";
        switch (ai.category) {
            case ApplicationInfo.CATEGORY_GAME: return "Games";
            case ApplicationInfo.CATEGORY_SOCIAL: return "Social";
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO: return "Entertainment";
            case ApplicationInfo.CATEGORY_IMAGE: return "Photo & Video";
            case ApplicationInfo.CATEGORY_NEWS: return "Information & Reading";
            case ApplicationInfo.CATEGORY_MAPS: return "Travel";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Productivity & Finance";
            default: return system ? "Utilities" : "Other";
        }
    }
}
