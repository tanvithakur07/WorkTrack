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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One e-mail that has to be sent: the "outbox" row. It is inserted in the SAME database transaction as the
 * business change that caused it (the leave request, the decision), so either both exist or neither does.
 * A separate worker sends it later and records the outcome.
 *
 * The rendered subject and bodies are stored, so the e-mail reflects the data at the moment of the decision
 * even if it is sent hours later after retries. They are never returned by any API and never logged.
 *
 * State changes happen only through guarded UPDATE statements in NotificationRepository (for example
 * "claim this row only if it is still PENDING and due"), which is what keeps two workers from sending
 * the same row; the entity therefore has no setters and no @Version.
 */
@Entity
@Table(
        name = "notifications",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_dedupe", columnNames = "dedupe_key"),
        indexes = {
                @Index(name = "idx_notification_due", columnList = "status,next_attempt_at"),
                @Index(name = "idx_notification_leave", columnList = "leave_request_id"),
                @Index(name = "idx_notification_subject", columnList = "subject_user_id,notification_type")
        })
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_email", nullable = false, length = 254)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 30, columnDefinition = "varchar(30)")
    private NotificationType type;

    /** The leave request this is about. A plain column (no foreign key): history must survive any cleanup. */
    @Column(name = "leave_request_id")
    private Long leaveRequestId;

    /** For example "LEAVE_APPROVED:42". Unique, so the same e-mail can never be queued twice. */
    @Column(name = "dedupe_key", nullable = false, length = 80)
    private String dedupeKey;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(name = "body_html", nullable = false, columnDefinition = "mediumtext")
    private String bodyHtml;

    @Column(name = "body_text", nullable = false, columnDefinition = "mediumtext")
    private String bodyText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /** While PROCESSING: when the worker's claim expires. After that another worker may take over. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** Class name plus a short, credential-free message. Never the message body. */
    @Column(name = "last_error", length = 500)
    private String lastError;

    /** How it was accepted: SMTP, or LOG when the development mode only wrote a log line. */
    @Column(length = 10)
    private String channel;

    /** The person the message is about (for example the invited employee). A plain column, no foreign key. */
    @Column(name = "subject_user_id")
    private Long subjectUserId;

    protected Notification() {
        // required by JPA
    }

    public Notification(String recipientEmail, NotificationType type, Long leaveRequestId, String dedupeKey,
                        String subject, String bodyHtml, String bodyText, Instant now) {
        this.recipientEmail = recipientEmail;
        this.type = type;
        this.leaveRequestId = leaveRequestId;
        this.dedupeKey = dedupeKey;
        this.subject = subject;
        this.bodyHtml = bodyHtml;
        this.bodyText = bodyText;
        this.status = NotificationStatus.PENDING;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    /** Marks whom the message is about; call before saving. Returns this for chaining. */
    public Notification aboutUser(Long userId) {
        this.subjectUserId = userId;
        return this;
    }

    public Long getSubjectUserId() {
        return subjectUserId;
    }

    public Long getId() {
        return id;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public NotificationType getType() {
        return type;
    }

    public Long getLeaveRequestId() {
        return leaveRequestId;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public String getSubject() {
        return subject;
    }

    public String getBodyHtml() {
        return bodyHtml;
    }

    public String getBodyText() {
        return bodyText;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public String getLastError() {
        return lastError;
    }

    public String getChannel() {
        return channel;
    }
}
