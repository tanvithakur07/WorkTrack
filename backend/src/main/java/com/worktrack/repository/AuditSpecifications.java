package com.worktrack.repository;

import com.worktrack.entity.AuditEvent;
import com.worktrack.entity.AuditOutcome;
import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

public final class AuditSpecifications {

    private AuditSpecifications() {
    }

    /** Every argument is optional; only the ones that are given narrow the result. */
    public static Specification<AuditEvent> filter(Instant from, Instant toExclusive, Long actorUserId, String action,
                                                   String entityType, Long entityId, AuditOutcome outcome) {
        Specification<AuditEvent> spec = (root, query, cb) -> cb.conjunction();
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
        }
        if (toExclusive != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("occurredAt"), toExclusive));
        }
        if (actorUserId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("actorUserId"), actorUserId));
        }
        if (action != null && !action.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("action"), action));
        }
        if (entityType != null && !entityType.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("entityType"), entityType));
        }
        if (entityId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("entityId"), entityId));
        }
        if (outcome != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("outcome"), outcome));
        }
        return spec;
    }
}
