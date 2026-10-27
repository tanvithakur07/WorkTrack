package com.worktrack.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure unit tests with a clock the test moves by hand, so nothing sleeps and nothing is flaky. */
class LoginAttemptLimiterTest {

    private static final String IP = "10.0.0.1";
    private static final String EMAIL = "victim@example.com";

    private MutableClock clock;
    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        limiter = new LoginAttemptLimiter(clock, 5, 30, Duration.ofMinutes(15),
                Duration.ofSeconds(60), Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("the first four failures are allowed, the fifth locks that IP and email for the base time")
    void locksAfterLimit() {
        failures(IP, EMAIL, 4);
        assertThat(limiter.retryAfterSeconds(IP, EMAIL)).isZero();

        limiter.recordFailure(IP, EMAIL);

        assertThat(limiter.retryAfterSeconds(IP, EMAIL)).isBetween(59L, 60L);
    }

    @Test
    @DisplayName("somebody else's address, or another email from the same address, is not locked")
    void lockIsScopedToTheIpAndEmailPair() {
        failures(IP, EMAIL, 5);

        assertThat(limiter.retryAfterSeconds("10.0.0.2", EMAIL)).isZero();
        assertThat(limiter.retryAfterSeconds(IP, "other@example.com")).isZero();
        assertThat(limiter.retryAfterSeconds(IP, "  VICTIM@Example.COM ")).isPositive();
    }

    @Test
    @DisplayName("the lock expires by itself; each further burst doubles it, up to the maximum")
    void progressiveLocks() {
        failures(IP, EMAIL, 5);
        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.retryAfterSeconds(IP, EMAIL)).isZero();

        failures(IP, EMAIL, 5);
        assertThat(limiter.retryAfterSeconds(IP, EMAIL)).isBetween(119L, 120L);

        clock.advance(Duration.ofSeconds(121));
        failures(IP, EMAIL, 5);
        assertThat(limiter.retryAfterSeconds(IP, EMAIL)).isBetween(239L, 240L);

        for (int round = 0; round < 6; round++) {
            clock.advance(Duration.ofMinutes(16));
            failures(IP, EMAIL, 5);
        }
        assertThat(limiter.retryAfterSeconds(IP, EMAIL)).isBetween(899L, 900L);
    }

    @Test
    @DisplayName("a successful sign-in clears that person's failures")
    void successResetsCounter() {
        failures(IP, EMAIL, 4);
        limiter.recordSuccess(IP, EMAIL);
        failures(IP, EMAIL, 4);

        assertThat(limiter.retryAfterSeconds(IP, EMAIL)).isZero();
    }

    @Test
    @DisplayName("failures older than the window are forgotten, and so is the lock history after a quiet spell")
    void windowAndHistoryExpire() {
        failures(IP, EMAIL, 4);
        clock.advance(Duration.ofMinutes(16));
        limiter.recordFailure(IP, EMAIL);
        assertThat(limiter.retryAfterSeconds(IP, EMAIL)).isZero();

        limiter.reset();
        failures(IP, EMAIL, 5);
        clock.advance(Duration.ofMinutes(40));
        failures(IP, EMAIL, 5);
        assertThat(limiter.retryAfterSeconds(IP, EMAIL)).isBetween(59L, 60L);
    }

    @Test
    @DisplayName("one address trying many different emails is stopped by the per-IP limit")
    void perIpLimit() {
        for (int i = 0; i < 29; i++) {
            limiter.recordFailure("10.9.9.9", "user" + i + "@example.com");
        }
        assertThat(limiter.retryAfterSeconds("10.9.9.9", "fresh@example.com")).isZero();

        limiter.recordFailure("10.9.9.9", "user30@example.com");

        assertThat(limiter.retryAfterSeconds("10.9.9.9", "fresh@example.com")).isPositive();
        assertThat(limiter.retryAfterSeconds("10.9.9.10", "fresh@example.com")).isZero();
    }

    @Test
    @DisplayName("memory stays bounded when somebody floods it with random addresses and emails")
    void memoryIsBounded() {
        for (int i = 0; i < 45_000; i++) {
            limiter.recordFailure("192.168." + (i % 250) + "." + (i / 250 % 250), "u" + i + "@x.com");
        }
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(22_000);
    }

    @Test
    @DisplayName("nonsense configuration is rejected at startup")
    void invalidConfiguration() {
        assertThatThrownBy(() -> new LoginAttemptLimiter(clock, 0, 30, Duration.ofMinutes(1),
                Duration.ofSeconds(1), Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptLimiter(clock, 5, 30, Duration.ofMinutes(1),
                Duration.ofMinutes(5), Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
    }

    private void failures(String ip, String email, int count) {
        for (int i = 0; i < count; i++) {
            limiter.recordFailure(ip, email);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-03-04T09:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
