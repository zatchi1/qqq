package com.wspace.calculator;

/**
 * Decides when the special "106" sound must be scheduled or cancelled (pure Java, unit-tested).
 *
 * Feed it the calculator's current expression after every change. It reports ARM when the screen has
 * just become exactly "106" (typed directly OR produced as a result), CANCEL when it stops being 106
 * before the sound played, and NONE otherwise - so the sound is never scheduled twice for the same 106.
 */
final class Trigger106 {

    enum Action { NONE, ARM, CANCEL }

    static final String VALUE = "106";
    static final long DELAY_MS = 2000;

    private boolean active = false;

    Action update(String expr) {
        boolean now = VALUE.equals(expr);
        Action a = now && !active ? Action.ARM : (!now && active ? Action.CANCEL : Action.NONE);
        active = now;
        return a;
    }

    /** True while the screen shows 106 (so the same 106 never re-arms). */
    boolean isActive() { return active; }

    void reset() { active = false; }
}
