package com.worktrack.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.worktrack.entity.LeaveType;

public record LeaveCreateRequest(
        @NotNull(message = "Leave type is required")
        LeaveType leaveType,

        @NotNull(message = "Start date is required")
        LocalDate startDate,

        @NotNull(message = "End date is required")
        LocalDate endDate,

        @NotBlank(message = "Please give a reason")
        @Size(min = 5, max = 500, message = "Reason must be between 5 and 500 characters")
        String reason) {
}
