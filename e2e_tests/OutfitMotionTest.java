package com.smartwardrobe.local;

import java.util.ArrayList;
import java.util.List;

/** Deterministic checks for posture signs, dead zone, hold timing and shake cooldown. */
public final class OutfitMotionTest {
    public static void main(String[] args) {
        List<String> events = new ArrayList<>();
        OutfitMotion motion = new OutfitMotion(new OutfitMotion.Actions() {
            public void step(int direction) { events.add("step" + direction); }
            public void focus(int row) { events.add("focus" + row); }
            public void shuffle() { events.add("shake"); }
        });
        motion.posture(40, 5, 1000);
        motion.posture(40, 17, 1010);
        check(events.isEmpty(), "12 degree dead zone relative to current grip");
        motion.posture(40, 21, 1100);
        check(events.equals(List.of("step1")), "right tilt advances once immediately");
        motion.posture(40, 21, 1499);
        check(events.size() == 1, "hold waits 400 ms");
        motion.posture(40, 21, 1500);
        motion.posture(40, 21, 1779);
        check(events.size() == 2, "normal repeat waits 280 ms");
        motion.posture(40, 40, 1780);
        motion.posture(40, 40, 1939);
        check(events.size() == 3, "accelerated repeat waits 160 ms");
        motion.posture(40, 40, 1940);
        check(events.size() == 4, "accelerated repeat");
        motion.posture(40, 5, 2000);
        motion.posture(40, -11, 2010);
        check(events.get(events.size() - 1).equals("step-1"), "left tilt goes backward");
        events.clear();
        motion.posture(59, 5, 2100);
        motion.posture(20, 5, 2599);
        check(events.equals(List.of("focus1")), "pitch down selects lower row and cools down");
        motion.posture(20, 5, 2600);
        check(events.equals(List.of("focus1", "focus0")), "pitch up selects upper row after 500 ms");
        events.clear();
        motion.acceleration(13, 3000);
        motion.acceleration(13.1f, 3001);
        motion.acceleration(20, 3800);
        check(events.equals(List.of("shake")), "shake threshold is strict and cooldown is 800 ms");
        motion.acceleration(14, 3801);
        check(events.size() == 2, "shake cooldown expires");
        motion.reset();
        events.clear();
        motion.acceleration(25, 5000);
        motion.posture(-30, -40, 5100);
        motion.posture(-30, -40, 5200);
        check(events.isEmpty(), "calibration and resume cannot spuriously move or shake");
        motion.posture(-30, -24, 5300);
        check(events.equals(List.of("step1")), "new zero point applied");
        motion.release();
        events.clear();
        motion.posture(-30, -24, 5400);
        check(events.equals(List.of("step1")), "finishing random or naming preserves the calibrated zero");
        motion.reset();
        events.clear();
        motion.posture(179, 0, 6000);
        motion.posture(-179, 0, 6010);
        check(events.isEmpty(), "pitch wraps across 180 degrees");
        System.out.println("OutfitMotionTest: all checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
