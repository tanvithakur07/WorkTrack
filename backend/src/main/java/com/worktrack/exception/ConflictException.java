package com.worktrack.exception;

/** The request is valid but clashes with current state (duplicate, overlap, wrong status). HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
