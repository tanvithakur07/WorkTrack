package com.worktrack.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveStatus;
import com.worktrack.entity.LeaveType;
import com.worktrack.entity.User;

public record LeaveResponse(
        Long id,
        Long employeeId,
        String employeeName,
        String employeeEmail,
        String department,
        LeaveType leaveType,
        LocalDate startDate,
        LocalDate endDate,
        int days,
        String reason,
        LeaveStatus status,
        Long reviewedById,
        String reviewedByName,
        Instant reviewedAt,
        String rejectionReason,
        Instant createdAt) {

    /** Call inside a transaction: it reads the lazily loaded employee and reviewer. */
    public static LeaveResponse from(LeaveRequest leave) {
        User employee = leave.getEmployee();
        User reviewer = leave.getReviewedBy();
        return new LeaveResponse(
                leave.getId(),
                employee.getId(),
                employee.getFullName(),
                employee.getEmail(),
                employee.getDepartment(),
                leave.getLeaveType(),
                leave.getStartDate(),
                leave.getEndDate(),
                leave.getDays(),
                leave.getReason(),
                leave.getStatus(),
                reviewer == null ? null : reviewer.getId(),
                reviewer == null ? null : reviewer.getFullName(),
                leave.getReviewedAt(),
                leave.getRejectionReason(),
                leave.getCreatedAt());
    }
}
