package com.worktrack.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.worktrack.entity.WorkLogStatus;

/**
 * Create and update body. There is deliberately no employee id: the owner always comes from the
 * signed-in session, so a client cannot write a report for somebody else.
 */
public record WorkLogRequest(
        @NotNull(message = "Work date is required")
        LocalDate workDate,

        @NotBlank(message = "Task title is required")
        @Size(min = 3, max = 150, message = "Task title must be between 3 and 150 characters")
        String taskTitle,

        @NotBlank(message = "Please describe what you did")
        @Size(min = 5, max = 2000, message = "Description must be between 5 and 2000 characters")
        String description,

        @NotNull(message = "Hours spent is required")
        @DecimalMin(value = "0.25", message = "Hours must be at least 0.25")
        @DecimalMax(value = "24", message = "Hours cannot be more than 24")
        @Digits(integer = 2, fraction = 2, message = "Hours can have at most 2 decimal places")
        BigDecimal hoursSpent,

        @NotNull(message = "Status is required")
        WorkLogStatus status) {
}
