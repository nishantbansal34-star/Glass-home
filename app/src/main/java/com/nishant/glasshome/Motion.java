package com.nishant.glasshome;

import android.animation.ValueAnimator;
import android.view.animation.PathInterpolator;

/** Shared motion tokens, so every animation in the launcher speaks the same language. */
final class Motion {
    private Motion() { }

    /** Strong ease-out for entering/exiting UI. */
    static final PathInterpolator EASE_OUT = new PathInterpolator(0.23f, 1f, 0.32f, 1f);
    /** Strong ease-in-out for things moving across the screen. */
    static final PathInterpolator EASE_IN_OUT = new PathInterpolator(0.77f, 0f, 0.175f, 1f);

    // Spring presets: {stiffness, damping ratio}. Ratio 1 never overshoots.
    static final float[] PRESS_IN = {1400f, 1f};   // ~100 ms: finger down
    static final float[] PRESS_OUT = {700f, 1f};   // ~160 ms: release
    static final float[] OPEN = {420f, 1f};        // menus, alerts (~250 ms visually)
    static final float[] CLOSE = {900f, 1f};       // closing is always faster than opening
    static final float[] FOLDER_OPEN = {300f, 0.92f};  // spatial morph out of the icon, tiny settle
    static final float[] FOLDER_CLOSE = {520f, 1f};
    static final float[] PAGE = {320f, 1f};
    static final float[] MORPH = {520f, 1f};       // pill ↔ page dots

    /** Android's "Remove animations" (accessibility) or developer animation scale of 0. */
    static boolean reduced() {
        return !ValueAnimator.areAnimatorsEnabled();
    }

    static void tune(Spring s, float[] p) { s.tune(p[0], p[1]); }
}
