package com.worktrack.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Sends through Spring's JavaMailSender (SMTP settings come from the environment, see application.properties). */
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;

    public SmtpEmailSender(JavaMailSender mailSender, String fromAddress, String fromName) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    @Override
    public void send(OutboundEmail email) {
        MimeMessage message;
        try {
            message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(email.to());
            helper.setSubject(email.subject());
            helper.setText(email.text(), email.html()); // plain text first, HTML as the alternative
        } catch (MessagingException | UnsupportedEncodingException | RuntimeException invalid) {
            // the message could not even be built (for example a malformed address): trying again cannot help
            throw new EmailDeliveryException("Message could not be built", true, invalid);
        }
        try {
            mailSender.send(message);
        } catch (MailAuthenticationException authenticationFailure) {
            throw new EmailDeliveryException("SMTP authentication failed", true, authenticationFailure);
        } catch (MailParseException parseFailure) {
            throw new EmailDeliveryException("Message was rejected as malformed", true, parseFailure);
        } catch (MailSendException sendFailure) {
            throw new EmailDeliveryException("SMTP send failed", isPermanent(sendFailure), sendFailure);
        } catch (MailException other) {
            throw new EmailDeliveryException("Mail system error", false, other);
        }
    }

    /** True only for causes that will fail the same way every time. Everything else may be temporary. */
    static boolean isPermanent(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof AuthenticationFailedException || t instanceof AddressException) {
                return true;
            }
            if (t instanceof SendFailedException sendFailed
                    && sendFailed.getInvalidAddresses() != null && sendFailed.getInvalidAddresses().length > 0) {
                return true;
            }
            if (t instanceof MailSendException many && many.getMessageExceptions() != null) {
                for (Exception inner : many.getMessageExceptions()) {
                    if (inner != t && isPermanent(inner)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public String channel() {
        return "SMTP";
    }
}
