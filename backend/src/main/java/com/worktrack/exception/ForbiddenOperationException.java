package com.worktrack.exception;

/** The signed-in user is not allowed to do this to this record. Maps to HTTP 403. */
public class ForbiddenOperationException extends RuntimeException {

    public ForbiddenOperationException(String message) {
        super(message);
    }
}
