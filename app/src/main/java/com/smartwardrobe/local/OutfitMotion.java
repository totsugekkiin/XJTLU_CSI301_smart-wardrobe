package com.smartwardrobe.local;

/** Relative posture policy, independent of Android so timing and hysteresis can be checked. */
final class OutfitMotion {
    interface Actions {
        void step(int direction);
        void focus(int row);
        void shuffle();
    }

    private final Actions actions;
    private float basePitch, baseRoll;
    private boolean calibrated;
    private int direction;
    private long heldSince, nextStep, lastFocus = -500, lastShake = -800;

    OutfitMotion(Actions actions) { this.actions = actions; }

    void reset() { calibrated = false; direction = 0; }
    void release() { direction = 0; }

    void posture(float pitch, float roll, long now) {
        if (!calibrated) {
            basePitch = pitch;
            baseRoll = roll;
            calibrated = true;
            direction = 0;
            return;
        }
        float p = relative(pitch, basePitch), r = relative(roll, baseRoll);
        if (now - lastFocus >= 500 && Math.abs(p) > 18) {
            actions.focus(p > 0 ? 1 : 0);
            lastFocus = now;
        }
        if (Math.abs(r) <= 12) { direction = 0; return; }
        int requested = r > 15 ? 1 : r < -15 ? -1 : 0;
        if (requested == 0) {
            // The 12–15 degree band retains hysteresis but does not count as a hold.
            heldSince = now;
            nextStep = now + 400;
            return;
        }
        if (direction != requested) {
            direction = requested;
            heldSince = now;
            nextStep = now + 400;
            actions.step(direction);
        } else if (now - heldSince >= 400 && now >= nextStep) {
            actions.step(direction);
            nextStep = now + (Math.abs(r) > 30 ? 160 : 280);
        }
    }

    void acceleration(float magnitude, long now) {
        if (calibrated && magnitude > 13 && now - lastShake >= 800) {
            lastShake = now;
            actions.shuffle();
        }
    }

    private static float relative(float value, float base) {
        return (value - base + 540) % 360 - 180;
    }
}
