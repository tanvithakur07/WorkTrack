-- ============================================================================
-- WorkTrack database schema (MySQL 8.x, InnoDB, utf8mb4)
--
-- HOW TO USE THIS FILE
--   The application creates and upgrades its own tables on startup with Flyway, using the versioned
--   scripts in src/main/resources/db/migration (V1, V2, ...). That is the source of truth.
--   This file is a readable, always-current snapshot of the final schema for people who want to
--   set the database up by hand or review it. If you run it manually, start the application once
--   afterwards: Flyway baselines the existing tables at version 1 and applies the later migrations,
--   which are written to be safe when this snapshot already contains their changes.
--   Hibernate never changes the schema (spring.jpa.hibernate.ddl-auto=validate).
--
-- This file mirrors the JPA entities (User, LeaveRequest, DailyWorkLog). If you change an entity,
-- change this file in the same commit.
--
-- RELATIONSHIP BETWEEN ACCOUNT AND EMPLOYEE
--   There is one table, `users`. A row is both the login account (email + password hash + role)
--   and the employee profile (name, department, job title, joining date, status). They are the same
--   record, so they can never disagree. The "Employee ID" shown in the UI (EMP-0001) is derived
--   from `users.id`; it is not stored twice.
-- ============================================================================

-- 1. Create the database and a least-privilege application user (run as a MySQL admin).
--    Replace the password. Never commit the real one.
CREATE DATABASE IF NOT EXISTS worktrack
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

-- CREATE USER 'worktrack_app'@'localhost' IDENTIFIED BY 'replace-with-a-strong-password';
-- GRANT SELECT, INSERT, UPDATE, DELETE ON worktrack.* TO 'worktrack_app'@'localhost';
-- (Flyway runs the migrations on startup, so the app user also needs CREATE, ALTER and INDEX on worktrack.*
--  - or run migrations with a separate privileged user, see .env.example.)

USE worktrack;

-- 2. Tables ------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    full_name     VARCHAR(120) NOT NULL,
    email         VARCHAR(180) NOT NULL,                 -- stored trimmed and lower-case
    password_hash VARCHAR(100) NOT NULL,                 -- BCrypt hash, never the password
    department    VARCHAR(80)  NOT NULL,
    job_title     VARCHAR(100) NOT NULL,
    joining_date  DATE         NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE | INACTIVE
    role          VARCHAR(20)  NOT NULL DEFAULT 'EMPLOYEE',  -- ADMIN | EMPLOYEE
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'EMPLOYEE')),
    INDEX idx_users_department (department),
    INDEX idx_users_status (status),
    INDEX idx_users_role (role)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS leave_requests (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    employee_id      BIGINT       NOT NULL,                       -- who the leave is for
    leave_type       VARCHAR(20)  NOT NULL,                       -- CASUAL | SICK | EARNED
    start_date       DATE         NOT NULL,
    end_date         DATE         NOT NULL,
    days             INT          NOT NULL,                       -- working days (Mon-Fri), inclusive
    reason           VARCHAR(500) NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',     -- PENDING | APPROVED | REJECTED | CANCELLED
    reviewed_by      BIGINT       NULL,                           -- the administrator who decided
    reviewed_at      DATETIME(6)  NULL,
    rejection_reason VARCHAR(500) NULL,
    version          BIGINT       NULL,                           -- optimistic locking (JPA @Version)
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_leave_employee FOREIGN KEY (employee_id) REFERENCES users (id),
    CONSTRAINT fk_leave_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id),
    CONSTRAINT ck_leave_type CHECK (leave_type IN ('CASUAL', 'SICK', 'EARNED')),
    CONSTRAINT ck_leave_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_leave_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_leave_days CHECK (days >= 1),
    INDEX idx_leave_employee_status (employee_id, status),
    INDEX idx_leave_status (status),
    INDEX idx_leave_dates (start_date, end_date),
    INDEX idx_leave_employee_status_dates (employee_id, status, start_date, end_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Daily Work Tracker (migration V3): one row = one task entry of a person's daily report.
CREATE TABLE IF NOT EXISTS daily_work_logs (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    employee_id BIGINT        NOT NULL,                        -- the author; set from the session, never from the client
    work_date   DATE          NOT NULL,
    task_title  VARCHAR(150)  NOT NULL,
    description VARCHAR(2000) NOT NULL,
    hours_spent DECIMAL(4, 2) NOT NULL,                        -- 0.25 to 24.00
    status      VARCHAR(20)   NOT NULL DEFAULT 'IN_PROGRESS',  -- DRAFT | IN_PROGRESS | COMPLETED | BLOCKED
    version     BIGINT        NULL,                            -- optimistic locking (JPA @Version)
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_worklog_employee FOREIGN KEY (employee_id) REFERENCES users (id),
    CONSTRAINT ck_worklog_status CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'COMPLETED', 'BLOCKED')),
    CONSTRAINT ck_worklog_hours CHECK (hours_spent >= 0.25 AND hours_spent <= 24),
    INDEX idx_worklog_employee_date (employee_id, work_date),
    INDEX idx_worklog_date (work_date),
    INDEX idx_worklog_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Notification outbox (migration V4) ---------------------------------------------
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
    subject_user_id   BIGINT       NULL,                     -- the person the message is about (V6)
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_dedupe UNIQUE (dedupe_key),
    CONSTRAINT ck_notification_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
    INDEX idx_notification_due (status, next_attempt_at),
    INDEX idx_notification_leave (leave_request_id),
    INDEX idx_notification_subject (subject_user_id, notification_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Audit history (migration V5) -------------------------------------------------
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

-- 2b. One-time account links (V6) ---------------------------------------------
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

-- 3. Initial administrator -----------------------------------------------------
-- Do NOT insert an admin by hand. Start the application once with WORKTRACK_ADMIN_EMAIL and
-- WORKTRACK_ADMIN_PASSWORD set (see .env.example); it creates the first administrator with a
-- BCrypt-hashed password when no ADMIN exists yet.
