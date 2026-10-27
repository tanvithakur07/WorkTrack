-- V1: initial WorkTrack schema (MySQL 8, InnoDB, utf8mb4).
-- Mirrors the JPA entities User and LeaveRequest. Never edit this file after it has been applied
-- anywhere: Flyway stores its checksum. Add a new V<number>__description.sql file instead.
--
-- Databases that were created earlier by Hibernate (ddl-auto=update) or by database/schema.sql are
-- baselined at version 1 (spring.flyway.baseline-version=1), so this script is skipped for them
-- and only the later migrations run.

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
    INDEX idx_leave_dates (start_date, end_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
