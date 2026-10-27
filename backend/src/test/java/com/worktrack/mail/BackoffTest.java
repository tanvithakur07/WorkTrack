package com.worktrack.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackoffTest {

    private static final Duration BASE = Duration.ofSeconds(60);
    private static final Duration CAP = Duration.ofMinutes(360);

    @Test
    @DisplayName("the delay doubles with every failed attempt")
    void doubles() {
        assertThat(Backoff.delayAfter(1, BASE, CAP)).isEqualTo(Duration.ofMinutes(1));
        assertThat(Backoff.delayAfter(2, BASE, CAP)).isEqualTo(Duration.ofMinutes(2));
        assertThat(Backoff.delayAfter(3, BASE, CAP)).isEqualTo(Duration.ofMinutes(4));
        assertThat(Backoff.delayAfter(4, BASE, CAP)).isEqualTo(Duration.ofMinutes(8));
    }

    @Test
    @DisplayName("it never exceeds the cap, even for huge attempt counts")
    void capped() {
        assertThat(Backoff.delayAfter(12, BASE, CAP)).isEqualTo(CAP);
        assertThat(Backoff.delayAfter(500, BASE, CAP)).isEqualTo(CAP);
        assertThat(Backoff.delayAfter(Integer.MAX_VALUE, BASE, CAP)).isEqualTo(CAP);
    }

    @Test
    @DisplayName("attempt 0 or below is treated as the first delay")
    void lowAttempts() {
        assertThat(Backoff.delayAfter(0, BASE, CAP)).isEqualTo(BASE);
        assertThat(Backoff.delayAfter(-3, BASE, CAP)).isEqualTo(BASE);
    }
}
