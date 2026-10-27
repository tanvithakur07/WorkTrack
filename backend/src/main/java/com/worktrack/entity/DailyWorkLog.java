package com.worktrack.entity;

import java.math.BigDecimal;
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
 * One task entry of a daily work report. An employee may have several entries for the same day.
 * The owner is set once from the authenticated session when the entry is created and never changes.
 * Like LeaveRequest, the relationship is one-directional (entry -> user).
 */
@Entity
@Table(
        name = "daily_work_logs",
        indexes = {
                @Index(name = "idx_worklog_employee_date", columnList = "employee_id,work_date"),
                @Index(name = "idx_worklog_date", columnList = "work_date"),
                @Index(name = "idx_worklog_status", columnList = "status")
        })
public class DailyWorkLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_worklog_employee"))
    private User employee;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "task_title", nullable = false, length = 150)
    private String taskTitle;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(name = "hours_spent", nullable = false, precision = 4, scale = 2, columnDefinition = "decimal(4,2)")
    private BigDecimal hoursSpent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private WorkLogStatus status = WorkLogStatus.IN_PROGRESS;

    /** Optimistic locking: editing the same entry from two tabs -> the second save gets a 409. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DailyWorkLog() {
        // required by JPA
    }

    public DailyWorkLog(User employee, LocalDate workDate, String taskTitle, String description,
                        BigDecimal hoursSpent, WorkLogStatus status) {
        this.employee = employee;
        update(workDate, taskTitle, description, hoursSpent, status);
    }

    /** Everything an author may change. The owner, id and creation time are deliberately not here. */
    public void update(LocalDate workDate, String taskTitle, String description,
                       BigDecimal hoursSpent, WorkLogStatus status) {
        this.workDate = workDate;
        this.taskTitle = taskTitle;
        this.description = description;
        this.hoursSpent = hoursSpent;
        this.status = status;
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

    public Long getId() {
        return id;
    }

    public User getEmployee() {
        return employee;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public String getTaskTitle() {
        return taskTitle;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getHoursSpent() {
        return hoursSpent;
    }

    public WorkLogStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
