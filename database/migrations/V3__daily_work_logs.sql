-- V3: Daily Work Tracker. Adds ONE new table; nothing existing is altered, copied or deleted, so it is
-- safe to run on a database that already holds users and leave requests.
-- One row = one task entry of a person's daily report (several rows per person per day are normal).

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
