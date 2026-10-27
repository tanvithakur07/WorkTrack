package com.worktrack.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.Role;

/** Body of POST /api/employees. Role defaults to EMPLOYEE and status to ACTIVE when omitted. */
public record CreateEmployeeRequest(
        @NotBlank(message = "Full name is required")
        @Size(max = 120, message = "Full name must be at most 120 characters")
        String fullName,

        @NotBlank(message = "Email is required")
        @Email(message = "Enter a valid email address")
        @Size(max = 180, message = "Email must be at most 180 characters")
        String email,

        @NotBlank(message = "A temporary password is required")
        @Pattern(regexp = PasswordRules.REGEX, message = PasswordRules.MESSAGE)
        String password,

        @NotBlank(message = "Department is required")
        @Size(max = 80, message = "Department must be at most 80 characters")
        String department,

        @NotBlank(message = "Job title is required")
        @Size(max = 100, message = "Job title must be at most 100 characters")
        String jobTitle,

        @NotNull(message = "Joining date is required")
        LocalDate joiningDate,

        Role role,

        EmploymentStatus status) {
}
