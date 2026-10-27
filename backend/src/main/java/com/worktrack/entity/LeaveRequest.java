package com.worktrack.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A leave request. State changes go through approve/reject/cancel so the entity itself
 * keeps the review fields (who, when, why) consistent with the status.
 *
 * The relationship is intentionally one-directional (leave -> user): the User entity does
 * not hold a collection of leaves, which also rules out infinite JSON recursion.
 */
@Entity
@Table(
        name = "leave_requests",
        indexes = {
                @Index(name = "idx_leave_employee_status", columnList = "employee_id,status"),
                @Index(name = "idx_leave_status", columnList = "status"),
                @Index(name = "idx_leave_dates", columnList = "start_date,end_date"),
                @Index(name = "idx_leave_employee_status_dates",
                        columnList = "employee_id,status,start_date,end_date")
        })
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, foreignKey = @ForeignKey(name = "fk_leave_employee"))
    private User employee;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 20, columnDefinition = "varchar(20)")
    private LeaveType leaveType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** Working days (Monday to Friday) between start and end, inclusive. */
    @Column(nullable = false)
    private int days;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private LeaveStatus status = LeaveStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by", foreignKey = @ForeignKey(name = "fk_leave_reviewer"))
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    /** Optimistic locking: two admins deciding on the same request at once -> one gets a 409. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LeaveRequest() {
        // required by JPA
    }

    public LeaveRequest(User employee, LeaveType leaveType, LocalDate startDate, LocalDate endDate,
                        int days, String reason) {
        this.employee = employee;
        this.leaveType = leaveType;
        this.startDate = startDate;
        this.endDate = endDate;
        this.days = days;
        this.reason = reason;
        this.status = LeaveStatus.PENDING;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void approve(User reviewer, Instant when) {
        this.status = LeaveStatus.APPROVED;
        this.reviewedBy = reviewer;
        this.reviewedAt = when;
        this.rejectionReason = null;
    }

    public void reject(User reviewer, Instant when, String reason) {
        this.status = LeaveStatus.REJECTED;
        this.reviewedBy = reviewer;
        this.reviewedAt = when;
        this.rejectionReason = reason;
    }

    public void cancel() {
        this.status = LeaveStatus.CANCELLED;
    }

    public Long getId() {
        return id;
    }

    public User getEmployee() {
        return employee;
    }

    public LeaveType getLeaveType() {
        return leaveType;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public int getDays() {
        return days;
    }

    public String getReason() {
        return reason;
    }

    public LeaveStatus getStatus() {
        return status;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
