package com.worktrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LeaveRejectRequest(
        @NotBlank(message = "A rejection reason is required")
        @Size(min = 3, max = 500, message = "Rejection reason must be between 3 and 500 characters")
        String reason) {
}
