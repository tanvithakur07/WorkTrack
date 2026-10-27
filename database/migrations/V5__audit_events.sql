-- V5: audit history. Adds ONE new table; nothing existing is altered, copied or deleted.
-- Append-only by design (the application never updates or deletes a row). Not tamper-proof: see the README.
-- There is no foreign key to users, so the history outlives any change to a user row.

CREATE TABLE IF NOT EXISTS audit_events (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    occurred_at    DATETIME(6)   NOT NULL,
    actor_user_id  BIGINT        NULL,                  -- from the server-side session; NULL for a failed login
    actor_role     VARCHAR(20)   NULL,
    actor_label    VARCHAR(254)  NULL,                  -- the actor's e-mail at the time
    action         VARCHAR(40)   NOT NULL,              -- e.g. LEAVE_APPROVED, LOGIN_FAILURE
    entity_type    VARCHAR(40)   NULL,
    entity_id      BIGINT        NULL,
    outcome        VARCHAR(10)   NOT NULL,              -- SUCCESS | FAILURE | DENIED
    correlation_id VARCHAR(64)   NULL,                  -- same id as the X-Request-Id header and the log lines
    client_ip      VARCHAR(45)   NULL,
    summary        VARCHAR(1000) NULL,                  -- sanitised; never passwords, tokens or message bodies
    PRIMARY KEY (id),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    INDEX idx_audit_time (occurred_at),
    INDEX idx_audit_actor (actor_user_id, occurred_at),
    INDEX idx_audit_entity (entity_type, entity_id),
    INDEX idx_audit_action (action, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
