package com.worktrack.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;

/** What the API returns for an employee. There is deliberately no password field of any kind. */
public record EmployeeResponse(
        Long id,
        String employeeCode,
        String fullName,
        String email,
        String department,
        String jobTitle,
        LocalDate joiningDate,
        EmploymentStatus status,
        Role role,
        Instant createdAt,
        Instant updatedAt,
        String invitation) {

    public static EmployeeResponse from(User user) {
        return new EmployeeResponse(
                user.getId(),
                String.format("EMP-%04d", user.getId()),
                user.getFullName(),
                user.getEmail(),
                user.getDepartment(),
                user.getJobTitle(),
                user.getJoiningDate(),
                user.getStatus(),
                user.getRole(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                null);
    }

    /**
     * The same employee plus what happened to the invitation e-mail just requested (QUEUED, ALREADY_QUEUED,
     * SKIPPED_INACTIVE_ACCOUNT or SKIPPED_INVALID_EMAIL). null in every other response.
     */
    public EmployeeResponse withInvitation(String result) {
        return new EmployeeResponse(id, employeeCode, fullName, email, department, jobTitle, joiningDate, status, role,
                createdAt, updatedAt, result);
    }
}
