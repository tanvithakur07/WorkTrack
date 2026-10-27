package com.worktrack.dto;

import com.worktrack.entity.AuditEvent;
import com.worktrack.entity.AuditOutcome;
import java.time.Instant;

public record AuditEventResponse(
        Long id,
        Instant occurredAt,
        Long actorUserId,
        String actorRole,
        String actorLabel,
        String action,
        String entityType,
        Long entityId,
        AuditOutcome outcome,
        String correlationId,
        String clientIp,
        String summary) {

    public static AuditEventResponse from(AuditEvent e) {
        return new AuditEventResponse(e.getId(), e.getOccurredAt(), e.getActorUserId(), e.getActorRole(),
                e.getActorLabel(), e.getAction(), e.getEntityType(), e.getEntityId(), e.getOutcome(),
                e.getCorrelationId(), e.getClientIp(), e.getSummary());
    }
}
