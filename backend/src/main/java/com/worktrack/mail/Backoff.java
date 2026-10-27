package com.worktrack.mail;

import java.time.Duration;

/** Exponential back-off: base, 2x base, 4x base ... never more than the cap. */
public final class Backoff {

    private Backoff() {
    }

    /** @param attempt number of attempts already made (1 = the first one just failed) */
    public static Duration delayAfter(int attempt, Duration base, Duration cap) {
        int exponent = Math.max(0, Math.min(attempt - 1, 30)); // 2^30 is far beyond any cap, and cannot overflow
        long seconds;
        try {
            seconds = Math.multiplyExact(base.getSeconds(), 1L << exponent);
        } catch (ArithmeticException overflow) {
            return cap;
        }
        Duration delay = Duration.ofSeconds(seconds);
        return delay.compareTo(cap) > 0 ? cap : delay;
    }
}
