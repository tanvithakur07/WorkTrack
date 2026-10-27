package com.worktrack.dto;

/**
 * One definition of the password policy, shared by every request that accepts a password.
 * At least 8 characters, at least one letter and one digit, at most 72 (BCrypt's input limit).
 */
public final class PasswordRules {

    public static final String REGEX = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$";
    public static final String MESSAGE =
            "Password must be 8-72 characters and include at least one letter and one number";

    private PasswordRules() {
    }
}
