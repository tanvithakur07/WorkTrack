-- V2: one index that serves the two hot leave queries, both of which filter on
-- employee + status + a date range: the overlap check done on every submit/approve, and the annual
-- leave total on the employee dashboard.
--
-- MySQL has no "CREATE INDEX IF NOT EXISTS", so the statement is built conditionally. That makes this
-- migration safe on a database that already has the index (for example one set up by hand with
-- database/schema.sql, which contains the final schema).
SET @worktrack_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'leave_requests'
      AND index_name = 'idx_leave_employee_status_dates');

SET @worktrack_index_ddl = IF(@worktrack_index_exists = 0,
    'CREATE INDEX idx_leave_employee_status_dates ON leave_requests (employee_id, status, start_date, end_date)',
    'SELECT 1');

PREPARE worktrack_index_stmt FROM @worktrack_index_ddl;
EXECUTE worktrack_index_stmt;
DEALLOCATE PREPARE worktrack_index_stmt;
