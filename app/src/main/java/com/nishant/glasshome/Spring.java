package com.nishant.glasshome;

/** A tiny damped spring used for every "liquid" motion (press swell, droplet preview, entrance). */
public class Spring {
    public float value, velocity, target;
    private final float stiffness, damping;

    public Spring(float stiffness, float dampingRatio) {
        this.stiffness = stiffness;
        this.damping = 2f * dampingRatio * (float) Math.sqrt(stiffness);
    }

    public Spring set(float v) { value = v; target = v; velocity = 0; return this; }

    /** Advances by dt seconds; returns true while still moving. */
    public boolean step(float dt) {
        // sub-step for stability on slow frames
        int n = Math.max(1, (int) Math.ceil(dt / 0.008f));
        float h = dt / n;
        for (int i = 0; i < n; i++) {
            float a = stiffness * (target - value) - damping * velocity;
            velocity += a * h;
            value += velocity * h;
        }
        if (Math.abs(target - value) < 0.002f && Math.abs(velocity) < 0.01f) {
            value = target;
            velocity = 0;
            return false;
        }
        return true;
    }
}
