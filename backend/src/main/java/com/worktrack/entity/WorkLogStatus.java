package com.worktrack.entity;

/**
 * Progress of one task in a daily report.
 * DRAFT means "still being written": drafts are private to the author, can be deleted, and are not
 * counted as reported hours or as a submitted day. The other three count as submitted.
 */
public enum WorkLogStatus {
    DRAFT,
    IN_PROGRESS,
    COMPLETED,
    BLOCKED
}
