package com.worktrack.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailConfigTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(sender);
        return provider;
    }

    private final MailConfig config = new MailConfig();

    @Test
    @DisplayName("log mode never needs SMTP settings and says so through its channel")
    void logMode() {
        EmailSender sender = config.emailSender(provider(null), "log", "", "WorkTrack", "", "");
        assertThat(sender).isInstanceOf(LoggingEmailSender.class);
        assertThat(sender.channel()).isEqualTo("LOG");
    }

    @Test
    @DisplayName("smtp mode without a host stops the application with a clear, secret-free message")
    void smtpNeedsHost() {
        assertThatThrownBy(() -> config.emailSender(provider(new JavaMailSenderImpl()), "smtp", "noreply@example.com", "WorkTrack", "", "user@gmail.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_HOST");
    }

    @Test
    @DisplayName("smtp mode without any sender address stops the application; a user name that is an address is used as the sender")
    void smtpNeedsSender() {
        assertThatThrownBy(() -> config.emailSender(provider(new JavaMailSenderImpl()), "smtp", "", "WorkTrack", "smtp.example.com", "plain-user"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WORKTRACK_MAIL_FROM");
        EmailSender sender = config.emailSender(provider(new JavaMailSenderImpl()), "smtp", "", "WorkTrack", "smtp.gmail.com", "me@gmail.com");
        assertThat(sender).isInstanceOf(SmtpEmailSender.class);
        assertThat(sender.channel()).isEqualTo("SMTP");
    }

    @Test
    @DisplayName("an unknown mode is refused rather than silently dropping every e-mail")
    void unknownMode() {
        assertThatThrownBy(() -> config.emailSender(provider(null), "smpt", "", "WorkTrack", "", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'log' or 'smtp'");
    }

    @Test
    @DisplayName("Google's App Password is accepted with the spaces Google displays; other providers' passwords are left alone")
    void gmailAppPasswordSpaces() {
        JavaMailSenderImpl gmail = new JavaMailSenderImpl();
        gmail.setPassword("abcd efgh ijkl mnop");
        config.emailSender(provider(gmail), "smtp", "me@gmail.com", "WorkTrack", "smtp.gmail.com", "me@gmail.com");
        assertThat(gmail.getPassword()).isEqualTo("abcdefghijklmnop");

        JavaMailSenderImpl other = new JavaMailSenderImpl();
        other.setPassword("pass with spaces");
        config.emailSender(provider(other), "smtp", "noreply@example.com", "WorkTrack", "smtp.example.com", "user");
        assertThat(other.getPassword()).isEqualTo("pass with spaces");
    }

    @Test
    @DisplayName("the status summary says LOG mode loudly, never contains credentials, and flags a half-configured SMTP setup")
    void status() {
        MailStatus log = new MailStatus("log", "", "", 587, "", "", "http://localhost:8080", true);
        assertThat(log.snapshot().realDelivery()).isFalse();
        assertThat(log.snapshot().warnings()).anyMatch(w -> w.contains("LOG") && w.contains("NOT delivered"));

        MailStatus smtp = new MailStatus("smtp", "", "smtp.gmail.com", 587, "me@gmail.com", "s3cret-app-pass", "http://localhost:8080", false);
        MailStatus.Snapshot snap = smtp.snapshot();
        assertThat(snap.realDelivery()).isTrue();
        assertThat(snap.from()).isEqualTo("me@gmail.com");
        assertThat(snap.toString()).doesNotContain("s3cret-app-pass");
        assertThat(snap.warnings()).anyMatch(w -> w.contains("WORKTRACK_BASE_URL")).anyMatch(w -> w.contains("worker is switched off"));

        MailStatus incomplete = new MailStatus("smtp", "noreply@example.com", "smtp.example.com", 587, "", "", "https://wt.example", true);
        assertThat(incomplete.snapshot().warnings()).anyMatch(w -> w.contains("MAIL_USERNAME"));
        MailStatus mismatch = new MailStatus("smtp", "other@example.com", "smtp.gmail.com", 587, "me@gmail.com", "x", "https://wt.example", true);
        assertThat(mismatch.snapshot().warnings()).anyMatch(w -> w.contains("should equal MAIL_USERNAME"));
    }
}
