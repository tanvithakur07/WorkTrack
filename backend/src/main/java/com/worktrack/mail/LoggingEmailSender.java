package com.worktrack.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development mode (worktrack.mail.mode=log, the default): nothing leaves the machine. It logs that an
 * e-mail would have been sent, WITHOUT the recipient address or any of the content, and reports success
 * so the rest of the pipeline can be exercised. Rows handled this way carry channel "LOG".
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(OutboundEmail email) {
        log.info("Mail mode is 'log': e-mail not sent (subject length {}). Set WORKTRACK_MAIL_MODE=smtp to send for real.",
                email.subject().length());
    }

    @Override
    public String channel() {
        return "LOG";
    }
}
