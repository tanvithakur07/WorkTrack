package com.worktrack.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.worktrack.entity.DailyWorkLog;
import com.worktrack.entity.User;
import com.worktrack.entity.WorkLogStatus;

public record WorkLogResponse(
        Long id,
        Long employeeId,
        String employeeName,
        String employeeEmail,
        String department,
        LocalDate workDate,
        String taskTitle,
        String description,
        BigDecimal hoursSpent,
        WorkLogStatus status,
        Instant createdAt,
        Instant updatedAt) {

    /** Call inside a transaction: it reads the lazily loaded employee. */
    public static WorkLogResponse from(DailyWorkLog log) {
        User employee = log.getEmployee();
        return new WorkLogResponse(
                log.getId(),
                employee.getId(),
                employee.getFullName(),
                employee.getEmail(),
                employee.getDepartment(),
                log.getWorkDate(),
                log.getTaskTitle(),
                log.getDescription(),
                log.getHoursSpent(),
                log.getStatus(),
                log.getCreatedAt(),
                log.getUpdatedAt());
    }
}
