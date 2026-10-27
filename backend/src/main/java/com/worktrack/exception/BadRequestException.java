package com.worktrack.exception;

/** A business-rule or input problem the caller can fix. Maps to HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
