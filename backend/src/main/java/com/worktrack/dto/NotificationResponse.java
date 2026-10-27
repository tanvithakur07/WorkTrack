package com.worktrack.dto;

import com.worktrack.entity.Notification;
import com.worktrack.entity.NotificationStatus;
import com.worktrack.entity.NotificationType;
import java.time.Instant;

/** What an administrator may see about a queued e-mail. Deliberately NO subject or body text. */
public record NotificationResponse(
        Long id,
        NotificationType type,
        String recipientEmail,
        Long leaveRequestId,
        NotificationStatus status,
        int attempts,
        Instant createdAt,
        Instant lastAttemptAt,
        Instant sentAt,
        Instant nextAttemptAt,
        String lastError,
        String channel) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getRecipientEmail(), n.getLeaveRequestId(),
                n.getStatus(), n.getAttempts(), n.getCreatedAt(), n.getLastAttemptAt(), n.getSentAt(),
                n.getNextAttemptAt(), n.getLastError(), n.getChannel());
    }
}
