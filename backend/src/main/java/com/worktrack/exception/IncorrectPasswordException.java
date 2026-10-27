package com.worktrack.exception;

/**
 * The current password supplied for a password change was wrong. It is still an HTTP 400 with the same
 * message as before; the separate type exists so the controller can count it as a failed attempt.
 */
public class IncorrectPasswordException extends BadRequestException {

    public IncorrectPasswordException() {
        super("Current password is incorrect");
    }
}
