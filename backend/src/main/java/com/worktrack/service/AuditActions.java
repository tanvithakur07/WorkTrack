package com.worktrack.service;

import java.util.List;

/** The audit action names. Text in the database, so keep them stable. */
public final class AuditActions {

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String LOGIN_THROTTLED = "LOGIN_THROTTLED";
    public static final String LOGOUT = "LOGOUT";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String EMPLOYEE_CREATED = "EMPLOYEE_CREATED";
    public static final String EMPLOYEE_UPDATED = "EMPLOYEE_UPDATED";
    public static final String EMPLOYEE_DEACTIVATED = "EMPLOYEE_DEACTIVATED";
    public static final String ROLE_CHANGED = "ROLE_CHANGED";
    public static final String LEAVE_SUBMITTED = "LEAVE_SUBMITTED";
    public static final String LEAVE_APPROVED = "LEAVE_APPROVED";
    public static final String LEAVE_REJECTED = "LEAVE_REJECTED";
    public static final String LEAVE_CANCELLED = "LEAVE_CANCELLED";
    public static final String WORKLOG_CREATED = "WORKLOG_CREATED";
    public static final String WORKLOG_UPDATED = "WORKLOG_UPDATED";
    public static final String WORKLOG_DELETED = "WORKLOG_DELETED";
    public static final String WORKLOG_EXPORTED = "WORKLOG_EXPORTED";
    public static final String NOTIFICATION_RETRY = "NOTIFICATION_RETRY";
    public static final String EMPLOYEE_REACTIVATED = "EMPLOYEE_REACTIVATED";
    public static final String INVITATION_RESENT = "INVITATION_RESENT";
    public static final String PASSWORD_SETUP_COMPLETED = "PASSWORD_SETUP_COMPLETED";

    public static final List<String> ALL = List.of(
            LOGIN_SUCCESS, LOGIN_FAILURE, LOGIN_THROTTLED, LOGOUT, PASSWORD_CHANGED,
            EMPLOYEE_CREATED, EMPLOYEE_UPDATED, EMPLOYEE_DEACTIVATED, ROLE_CHANGED,
            LEAVE_SUBMITTED, LEAVE_APPROVED, LEAVE_REJECTED, LEAVE_CANCELLED,
            WORKLOG_CREATED, WORKLOG_UPDATED, WORKLOG_DELETED, WORKLOG_EXPORTED, NOTIFICATION_RETRY,
            EMPLOYEE_REACTIVATED, INVITATION_RESENT, PASSWORD_SETUP_COMPLETED);

    private AuditActions() {
    }
}
