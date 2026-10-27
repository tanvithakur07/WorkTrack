-- V6: secure one-time account links (invitation / first-login password setup) and the "person this
-- e-mail is about" column of the notification outbox. Additive: nothing existing is changed or removed.

-- Only a SHA-256 hash of the link secret is stored, never the secret itself, so a copy of this table
-- cannot be used to take over an account. A link is single-use and expires.
CREATE TABLE IF NOT EXISTS account_tokens (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    token_hash  CHAR(64)    NOT NULL,                    -- hex SHA-256 of the secret in the e-mailed link
    purpose     VARCHAR(20) NOT NULL,                    -- INVITATION
    created_at  DATETIME(6) NOT NULL,
    expires_at  DATETIME(6) NOT NULL,
    used_at     DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_account_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_account_token_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_account_token_user (user_id, purpose)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Which person an e-mail concerns. Used by messages that need a fresh secret link when they are SENT
-- (invitations), so the secret is never stored in the outbox. No foreign key: history outlives cleanups.
-- ALTER ... ADD COLUMN is not re-runnable, but Flyway runs each version exactly once.
ALTER TABLE notifications ADD COLUMN subject_user_id BIGINT NULL;
CREATE INDEX idx_notification_subject ON notifications (subject_user_id, notification_type);
