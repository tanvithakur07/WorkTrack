package com.worktrack.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Retry tuning, all overridable through environment variables (see application.properties). */
@Component
public class NotificationProperties {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Duration lease;
    private final int batchSize;
    private final String appName;
    private final String baseUrl;

    public NotificationProperties(
            @Value("${worktrack.notifications.max-attempts:6}") int maxAttempts,
            @Value("${worktrack.notifications.base-delay-seconds:60}") long baseDelaySeconds,
            @Value("${worktrack.notifications.max-delay-minutes:360}") long maxDelayMinutes,
            @Value("${worktrack.notifications.lease-seconds:120}") long leaseSeconds,
            @Value("${worktrack.notifications.batch-size:20}") int batchSize,
            @Value("${worktrack.mail.from-name:WorkTrack}") String appName,
            @Value("${worktrack.base-url:http://localhost:8080}") String baseUrl) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelay = Duration.ofSeconds(Math.max(1, baseDelaySeconds));
        this.maxDelay = Duration.ofMinutes(Math.max(1, maxDelayMinutes));
        this.lease = Duration.ofSeconds(Math.max(10, leaseSeconds));
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
        this.appName = appName;
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUrl = trimmed;
    }

    /** For tests that build the dispatcher without Spring. */
    public static NotificationProperties of(int maxAttempts, long baseDelaySeconds, long maxDelayMinutes,
                                            long leaseSeconds, int batchSize) {
        return new NotificationProperties(maxAttempts, baseDelaySeconds, maxDelayMinutes, leaseSeconds, batchSize, "WorkTrack",
                "https://worktrack.example.test");
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration baseDelay() {
        return baseDelay;
    }

    public Duration maxDelay() {
        return maxDelay;
    }

    public Duration lease() {
        return lease;
    }

    public int batchSize() {
        return batchSize;
    }

    public String appName() {
        return appName;
    }

    /** The public address of this application, without a trailing slash. Used for links inside e-mails. */
    public String baseUrl() {
        return baseUrl;
    }
}
