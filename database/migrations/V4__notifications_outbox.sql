-- V4: e-mail notification outbox. Adds ONE new table; nothing existing is altered, copied or deleted.
-- A row is inserted in the same transaction as the leave change that causes it and is sent later by a
-- background worker. SENT means the SMTP server accepted the message, not that anyone read it.

CREATE TABLE IF NOT EXISTS notifications (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    recipient_email   VARCHAR(254) NOT NULL,
    notification_type VARCHAR(30)  NOT NULL,                 -- LEAVE_SUBMITTED | LEAVE_APPROVED | LEAVE_REJECTED
    leave_request_id  BIGINT       NULL,                     -- no foreign key on purpose: history outlives cleanups
    dedupe_key        VARCHAR(80)  NOT NULL,                 -- e.g. LEAVE_APPROVED:42, unique: never queued twice
    subject           VARCHAR(200) NOT NULL,
    body_html         MEDIUMTEXT   NOT NULL,                 -- rendered when the decision was made; never returned by the API
    body_text         MEDIUMTEXT   NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING | PROCESSING | SENT | FAILED
    attempts          INT          NOT NULL DEFAULT 0,
    created_at        DATETIME(6)  NOT NULL,
    last_attempt_at   DATETIME(6)  NULL,
    sent_at           DATETIME(6)  NULL,
    next_attempt_at   DATETIME(6)  NULL,                     -- when a PENDING row becomes due
    locked_until      DATETIME(6)  NULL,                     -- while PROCESSING: end of the worker's claim
    last_error        VARCHAR(500) NULL,                     -- sanitised: class name and a short message, no secrets
    channel           VARCHAR(10)  NULL,                     -- SMTP, or LOG when only a log line was written
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_dedupe UNIQUE (dedupe_key),
    CONSTRAINT ck_notification_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
    INDEX idx_notification_due (status, next_attempt_at),
    INDEX idx_notification_leave (leave_request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
