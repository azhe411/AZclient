package com.lite.agent;
/** Monotonic seconds per rendered frame; a stalled/paused frame never teleports the camera. */
final class FrameClock {
    private long previous = Long.MIN_VALUE;
    void reset() { previous = Long.MIN_VALUE; }
    double step(long now) {
        long old = previous; previous = now;
        if (old == Long.MIN_VALUE || now <= old) return 0.0;
        double elapsed = (now - old) / 1000000000.0;
        return elapsed > 0.25 ? 0.0 : elapsed;
    }
}
