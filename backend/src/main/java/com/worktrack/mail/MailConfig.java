package com.worktrack.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Chooses how e-mail leaves the application:
 *  - worktrack.mail.mode=log   (default) nothing is sent; a log line is written. Safe for development and tests.
 *  - worktrack.mail.mode=smtp  real SMTP using the spring.mail.* settings (MAIL_HOST, MAIL_PORT, ...).
 * An unknown mode, or smtp without a host, stops the application at startup with a clear message,
 * instead of silently dropping every e-mail.
 */
@Configuration
public class MailConfig {

    @Bean
    public EmailSender emailSender(ObjectProvider<JavaMailSender> mailSenderProvider,
                                   @Value("${worktrack.mail.mode:log}") String mode,
                                   @Value("${worktrack.mail.from:}") String from,
                                   @Value("${worktrack.mail.from-name:WorkTrack}") String fromName,
                                   @Value("${spring.mail.host:}") String host,
                                   @Value("${spring.mail.username:}") String username) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase();
        if (normalized.equals("log")) {
            return new LoggingEmailSender();
        }
        if (normalized.equals("smtp")) {
            if (host == null || host.isBlank()) {
                throw new IllegalStateException(
                        "WORKTRACK_MAIL_MODE=smtp needs MAIL_HOST (and usually MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD). See .env.example.");
            }
            from = effectiveFrom(from, username);
            if (from.isBlank() || !from.contains("@")) {
                throw new IllegalStateException(
                        "WORKTRACK_MAIL_MODE=smtp needs WORKTRACK_MAIL_FROM (or MAIL_USERNAME as an e-mail address), the sender address.");
            }
            JavaMailSender sender = mailSenderProvider.getIfAvailable();
            if (sender == null) {
                throw new IllegalStateException("SMTP mode is on but no JavaMailSender could be created. Check the MAIL_* settings.");
            }
            // Google shows App Passwords in groups of four with spaces; the spaces are not part of the password.
            if (sender instanceof JavaMailSenderImpl impl && impl.getPassword() != null
                    && host.toLowerCase(java.util.Locale.ROOT).endsWith("gmail.com")) {
                impl.setPassword(impl.getPassword().replaceAll("\\s+", ""));
            }
            return new SmtpEmailSender(sender, from.trim(), fromName);
        }
        throw new IllegalStateException("worktrack.mail.mode must be 'log' or 'smtp' but was '" + mode + "'");
    }

    /** The sender address: WORKTRACK_MAIL_FROM, or the SMTP user name when that is an e-mail address (Gmail). */
    static String effectiveFrom(String from, String username) {
        if (from != null && !from.isBlank()) {
            return from.trim();
        }
        return username != null && username.contains("@") ? username.trim() : "";
    }
}
