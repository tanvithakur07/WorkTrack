package com.worktrack.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * One line of the audit history. Append-only by design: the class has no setters, Hibernate treats it
 * as {@code @Immutable} (it never issues an UPDATE), no API endpoint can change or delete one, and the
 * README describes taking UPDATE/DELETE rights away from the application's database user.
 *
 * What it is NOT: tamper-proof. Anyone with direct write access to the database (a DBA, a stolen
 * database password) can still change or remove rows. There is no hash chain or external log shipping.
 *
 * There is deliberately no foreign key to users: the history must outlive anything that happens to a
 * user row, and an audit insert must never fail because of a referential check.
 */
@Entity
@Immutable
@Table(
        name = "audit_events",
        indexes = {
                @Index(name = "idx_audit_time", columnList = "occurred_at"),
                @Index(name = "idx_audit_actor", columnList = "actor_user_id,occurred_at"),
                @Index(name = "idx_audit_entity", columnList = "entity_type,entity_id"),
                @Index(name = "idx_audit_action", columnList = "action,occurred_at")
        })
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** From the server-side session, never from the request. Null when nobody was signed in (a failed login). */
    @Column(name = "actor_user_id", updatable = false)
    private Long actorUserId;

    @Column(name = "actor_role", length = 20, updatable = false)
    private String actorRole;

    /** The actor's e-mail at the time (or the e-mail typed in a failed login), so the line stays readable later. */
    @Column(name = "actor_label", length = 254, updatable = false)
    private String actorLabel;

    @Column(nullable = false, length = 40, updatable = false)
    private String action;

    @Column(name = "entity_type", length = 40, updatable = false)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, columnDefinition = "varchar(10)", updatable = false)
    private AuditOutcome outcome;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @Column(name = "client_ip", length = 45, updatable = false)
    private String clientIp;

    /** A short, sanitised description. Never a password, token, session id or message body. */
    @Column(length = 1000, updatable = false)
    private String summary;

    protected AuditEvent() {
        // required by JPA
    }

    public AuditEvent(Instant occurredAt, Long actorUserId, String actorRole, String actorLabel, String action,
                      String entityType, Long entityId, AuditOutcome outcome, String correlationId,
                      String clientIp, String summary) {
        this.occurredAt = occurredAt;
        this.actorUserId = actorUserId;
        this.actorRole = actorRole;
        this.actorLabel = actorLabel;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.outcome = outcome;
        this.correlationId = correlationId;
        this.clientIp = clientIp;
        this.summary = summary;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getSummary() {
        return summary;
    }
}
