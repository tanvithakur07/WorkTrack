package com.worktrack.mail;

/**
 * An attempt to send failed. {@link #isPermanent()} tells the retry logic whether trying again could ever
 * help: a wrong SMTP password or a malformed address will fail every time, a network timeout may not.
 */
public class EmailDeliveryException extends RuntimeException {

    private final boolean permanent;

    public EmailDeliveryException(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
