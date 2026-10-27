package com.worktrack.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.worktrack.security.LoginAttemptLimiter;

@Configuration
public class AppConfig {

    /**
     * A single Clock bean makes "today" injectable, so date rules (for example the
     * past-date policy for leave requests) can be unit-tested with a fixed clock.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /** Sign-in throttling; every limit can be changed in application.properties / environment. */
    @Bean
    public LoginAttemptLimiter loginAttemptLimiter(
            Clock clock,
            @Value("${worktrack.security.login.max-failures:5}") int maxFailuresPerAccount,
            @Value("${worktrack.security.login.max-failures-per-ip:30}") int maxFailuresPerIp,
            @Value("${worktrack.security.login.window-minutes:15}") long windowMinutes,
            @Value("${worktrack.security.login.base-lock-seconds:60}") long baseLockSeconds,
            @Value("${worktrack.security.login.max-lock-minutes:15}") long maxLockMinutes) {
        return new LoginAttemptLimiter(clock, maxFailuresPerAccount, maxFailuresPerIp,
                Duration.ofMinutes(windowMinutes), Duration.ofSeconds(baseLockSeconds),
                Duration.ofMinutes(maxLockMinutes));
    }
}
