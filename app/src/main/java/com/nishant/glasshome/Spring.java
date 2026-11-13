package com.nishant.glasshome;

/**
 * A tiny damped spring used for every motion. Springs keep their velocity when retargeted, so any
 * animation can be interrupted or reversed mid-flight without a jump.
 */
public class Spring {
    public float value, velocity, target;
    private float stiffness, damping;

    /** dampingRatio 1 = no overshoot; 0.8–0.9 = a hint of bounce (keep for gestures only). */
    public Spring(float stiffness, float dampingRatio) {
        tune(stiffness, dampingRatio);
    }

    /** Change feel mid-flight, e.g. a faster spring for closing than for opening. */
    public Spring tune(float stiffness, float dampingRatio) {
        this.stiffness = stiffness;
        this.damping = 2f * dampingRatio * (float) Math.sqrt(stiffness);
        return this;
    }

    public Spring set(float v) { value = v; target = v; velocity = 0; return this; }

    public boolean atRest() { return value == target && velocity == 0; }

    /** Advances by dt seconds; returns true while still moving. */
    public boolean step(float dt) {
        if (atRest()) return false;
        // sub-step for stability on slow frames
        int n = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / n;
        for (int i = 0; i < n; i++) {
            float a = stiffness * (target - value) - damping * velocity;
            velocity += a * h;
            value += velocity * h;
        }
        if (Math.abs(target - value) < 0.0008f && Math.abs(velocity) < 0.004f) {
            value = target;
            velocity = 0;
            return false;
        }
        return true;
    }
}
