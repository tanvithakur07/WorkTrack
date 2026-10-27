package com.worktrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Email is required")
        @Size(max = 180, message = "Email is too long")
        String email,

        @NotBlank(message = "Password is required")
        @Size(max = 200, message = "Password is too long")
        String password) {
}
