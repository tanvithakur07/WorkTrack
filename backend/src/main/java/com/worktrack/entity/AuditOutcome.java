package com.worktrack.entity;

/** SUCCESS: it happened. FAILURE: it was attempted and failed (for example a wrong password). DENIED: refused by a rule. */
public enum AuditOutcome {
    SUCCESS,
    FAILURE,
    DENIED
}
