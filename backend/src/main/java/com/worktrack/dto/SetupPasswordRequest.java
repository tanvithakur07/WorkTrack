package com.worktrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /api/auth/setup-password: the secret from the invitation link and the password the person chose. */
public record SetupPasswordRequest(
        @NotBlank(message = "The link is incomplete")
        @Size(max = 200, message = "The link is not valid")
        String token,

        @NotBlank(message = "Choose a password")
        @Pattern(regexp = PasswordRules.REGEX, message = PasswordRules.MESSAGE)
        String password) {
}
