package com.worktrack.entity;

/**
 * Which e-mail a notification is. Stored as text (column length 30), so never reorder or rename without a
 * migration.
 *
 * LEAVE_SUBMITTED goes to the administrators (not to the employee who submitted); LEAVE_APPROVED and
 * LEAVE_REJECTED go to the employee the request belongs to.
 */
public enum NotificationType {
    LEAVE_SUBMITTED,
    LEAVE_APPROVED,
    LEAVE_REJECTED,
    LEAVE_CANCELLED,
    EMPLOYEE_INVITED,
    EMPLOYEE_UPDATED,
    EMPLOYEE_DEACTIVATED,
    EMPLOYEE_REACTIVATED,
    ACCOUNT_SECURITY
}
