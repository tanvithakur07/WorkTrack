package com.worktrack.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/** No real SMTP server and no network: the JavaMailSender is a mock. */
class SmtpEmailSenderTest {

    private JavaMailSender mailSender;
    private SmtpEmailSender sender;
    private final OutboundEmail email = new OutboundEmail("priya@example.com", "Subject", "<p>Hello</p>", "Hello");

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        sender = new SmtpEmailSender(mailSender, "noreply@worktrack.example", "WorkTrack");
    }

    @Test
    @DisplayName("a normal send builds a multipart message with both versions and hands it to the mail sender")
    void sendsMultipart() throws Exception {
        sender.send(email);

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        MimeMessage message = sent.getValue();
        message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("Subject");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("priya@example.com");
        assertThat(message.getFrom()[0].toString()).contains("noreply@worktrack.example");
        assertThat(message.getContentType()).startsWith("multipart/");
        assertThat(sender.channel()).isEqualTo("SMTP");
    }

    @Test
    @DisplayName("a wrong SMTP login is a PERMANENT failure: retrying cannot help")
    void authenticationFailureIsPermanent() {
        doThrow(new MailAuthenticationException("bad login")).when(mailSender).send(any(MimeMessage.class));
        assertThatThrownBy(() -> sender.send(email)).isInstanceOfSatisfying(EmailDeliveryException.class,
                e -> assertThat(e.isPermanent()).isTrue());
    }

    @Test
    @DisplayName("a malformed recipient address is permanent")
    void badAddressIsPermanent() {
        assertThatThrownBy(() -> sender.send(new OutboundEmail("not an address", "S", "<p>x</p>", "x")))
                .isInstanceOfSatisfying(EmailDeliveryException.class, e -> assertThat(e.isPermanent()).isTrue());
    }

    @Test
    @DisplayName("an invalid-address answer from the server is permanent")
    void rejectedAddressIsPermanent() {
        MailSendException failure = new MailSendException("rejected", new AddressException("bad"));
        assertThat(SmtpEmailSender.isPermanent(failure)).isTrue();
    }

    @Test
    @DisplayName("a connection problem or timeout is TEMPORARY and will be retried")
    void connectionProblemIsTemporary() {
        doThrow(new MailSendException("Mail server connection failed", new java.net.ConnectException("refused")))
                .when(mailSender).send(any(MimeMessage.class));
        assertThatThrownBy(() -> sender.send(email)).isInstanceOfSatisfying(EmailDeliveryException.class,
                e -> assertThat(e.isPermanent()).isFalse());
    }
}
