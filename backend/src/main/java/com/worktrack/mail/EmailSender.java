package com.worktrack.mail;

/**
 * Hands one message to a mail system. The notification worker depends on this interface only, so
 * tests (and the development mode) can replace the real SMTP implementation.
 */
public interface EmailSender {

    /**
     * Returns normally ONLY when the message was accepted by the mail server. That means the server
     * took responsibility for delivering it; it does NOT mean the recipient received or read it.
     */
    void send(OutboundEmail email) throws EmailDeliveryException;

    /** "SMTP" or "LOG": recorded on the notification so it is visible how it was "sent". */
    String channel();
}
