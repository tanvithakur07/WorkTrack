package com.worktrack.entity;

/**
 * PENDING    waiting for its next attempt (new, or waiting out a retry delay)
 * PROCESSING claimed by one worker right now (a lease, see Notification#getLockedUntil)
 * SENT       the SMTP server ACCEPTED the message. This is not proof that it reached an inbox.
 * FAILED     given up: a permanent error, or the maximum number of attempts was used
 */
public enum NotificationStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED
}
