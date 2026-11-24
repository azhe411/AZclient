package com.lite.agent;

/** Click deadlines sampled by the vanilla 20 Hz input loop; no catch-up queue. */
final class CombatCadence {
    private CombatCadence() {}
    static long period(double rate) {
        return Math.max(1L,Math.round(1000.0/Math.max(.1,rate)));
    }
    static long next(long deadline,long now,long period) {
        // One tick of quantization is normal (9 APS alternates 100/150 ms).
        // A lost aim/cooldown window is not: discard its overdue schedule so
        // reacquisition cannot compress the next pair of clicks to 50 ms.
        return deadline<=0 || now<deadline || now-deadline>=50L
                ? now+period : deadline+period;
    }
}
