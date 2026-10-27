package com.worktrack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.worktrack.entity.Notification;
import com.worktrack.entity.NotificationType;
import com.worktrack.mail.EmailDeliveryException;
import com.worktrack.mail.EmailSender;
import com.worktrack.mail.OutboundEmail;
import com.worktrack.repository.NotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit tests with mocks only: nothing here talks to a database or a mail server. */
@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-03-04T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private NotificationRepository repository;
    @Mock
    private EmailSender emailSender;
    @Mock
    private AccountTokenService accountTokens;

    private NotificationProperties properties;
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        // 3 attempts at most, 60 s base delay, 1 h cap, 120 s lease
        properties = NotificationProperties.of(3, 60, 60, 120, 20);
        dispatcher = new NotificationDispatcher(repository, emailSender, properties, CLOCK, accountTokens, "smtp-user", "s3cretPass");
    }

    private Notification notification(long id, int attemptsAfterClaim) {
        Notification n = new Notification("priya@example.com", NotificationType.LEAVE_APPROVED, 42L, "LEAVE_APPROVED:42",
                "Subject", "<p>Body</p>", "Body", NOW);
        ReflectionTestUtils.setField(n, "id", id);
        ReflectionTestUtils.setField(n, "attempts", attemptsAfterClaim);
        return n;
    }

    private void claimWorks(long id, Notification n) {
        when(repository.claim(eq(id), eq(NOW), eq(NOW.plus(Duration.ofSeconds(120))))).thenReturn(1);
        when(repository.findById(id)).thenReturn(Optional.of(n));
    }

    @Test
    @DisplayName("SENT is recorded only after the mail system accepted the message")
    void sentOnlyAfterAcceptance() {
        claimWorks(1L, notification(1L, 1));
        when(emailSender.channel()).thenReturn("SMTP");
        when(repository.markSent(1L, NOW, "SMTP")).thenReturn(1);

        assertThat(dispatcher.processOne(1L)).isTrue();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(emailSender, repository);
        order.verify(repository).claim(anyLong(), any(), any());
        order.verify(emailSender).send(any(OutboundEmail.class));
        order.verify(repository).markSent(1L, NOW, "SMTP");
    }

    @Test
    @DisplayName("what is sent is the stored message, addressed to the stored recipient")
    void sendsStoredContent() {
        claimWorks(1L, notification(1L, 1));
        when(emailSender.channel()).thenReturn("SMTP");

        dispatcher.processOne(1L);

        ArgumentCaptor<OutboundEmail> sent = ArgumentCaptor.forClass(OutboundEmail.class);
        verify(emailSender).send(sent.capture());
        assertThat(sent.getValue()).isEqualTo(new OutboundEmail("priya@example.com", "Subject", "<p>Body</p>", "Body"));
    }

    @Test
    @DisplayName("a temporary failure goes back to PENDING with the first back-off delay (60 s)")
    void temporaryFailureIsRetriedLater() {
        claimWorks(1L, notification(1L, 1));
        org.mockito.Mockito.doThrow(new EmailDeliveryException("SMTP send failed", false, new RuntimeException("timeout")))
                .when(emailSender).send(any());

        dispatcher.processOne(1L);

        verify(repository).markRetry(eq(1L), eq(NOW.plusSeconds(60)), anyString());
        verify(repository, never()).markSent(anyLong(), any(), anyString());
        verify(repository, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("the delay doubles with each attempt: second failure waits 120 s")
    void backoffGrows() {
        claimWorks(1L, notification(1L, 2));
        org.mockito.Mockito.doThrow(new EmailDeliveryException("SMTP send failed", false, null)).when(emailSender).send(any());

        dispatcher.processOne(1L);

        verify(repository).markRetry(eq(1L), eq(NOW.plusSeconds(120)), anyString());
    }

    @Test
    @DisplayName("a permanent failure gives up at once: FAILED after the first attempt")
    void permanentFailureStopsImmediately() {
        claimWorks(1L, notification(1L, 1));
        org.mockito.Mockito.doThrow(new EmailDeliveryException("SMTP authentication failed", true, null)).when(emailSender).send(any());

        dispatcher.processOne(1L);

        verify(repository).markFailed(eq(1L), anyString());
        verify(repository, never()).markRetry(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("when the attempts are used up the notification becomes FAILED instead of being retried forever")
    void attemptsExhausted() {
        claimWorks(1L, notification(1L, 3)); // the third and last allowed attempt
        org.mockito.Mockito.doThrow(new EmailDeliveryException("SMTP send failed", false, null)).when(emailSender).send(any());

        dispatcher.processOne(1L);

        verify(repository).markFailed(eq(1L), anyString());
        verify(repository, never()).markRetry(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("an unexpected runtime error is treated as temporary, not as a crash of the worker")
    void unexpectedErrorIsTemporary() {
        claimWorks(1L, notification(1L, 1));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(emailSender).send(any());

        assertThat(dispatcher.processOne(1L)).isTrue();

        verify(repository).markRetry(eq(1L), any(), anyString());
    }

    @Test
    @DisplayName("if another worker already claimed it, nothing is sent")
    void lostClaimSendsNothing() {
        when(repository.claim(anyLong(), any(), any())).thenReturn(0);

        assertThat(dispatcher.processOne(1L)).isFalse();

        verify(emailSender, never()).send(any());
        verify(repository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("the stored error never contains the SMTP user name, password or an address")
    void storedErrorIsSanitised() {
        claimWorks(1L, notification(1L, 1));
        org.mockito.Mockito.doThrow(new EmailDeliveryException("535 login failed for smtp-user with s3cretPass for bob@example.com", false, null))
                .when(emailSender).send(any());

        dispatcher.processOne(1L);

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(repository).markRetry(eq(1L), any(), error.capture());
        assertThat(error.getValue()).doesNotContain("smtp-user").doesNotContain("s3cretPass").doesNotContain("bob@example.com");
    }

    @Test
    @DisplayName("a batch handles every due notification and reports how many it actually processed")
    void batchProcessing() {
        when(repository.findDueIds(any(Instant.class), any(Pageable.class))).thenReturn(List.of(1L, 2L, 3L));
        claimWorks(1L, notification(1L, 1));
        when(repository.claim(eq(2L), any(), any())).thenReturn(0); // lost the race for #2
        claimWorks(3L, notification(3L, 1));
        when(emailSender.channel()).thenReturn("SMTP");

        assertThat(dispatcher.processDue()).isEqualTo(2);

        verify(emailSender, org.mockito.Mockito.times(2)).send(any());
    }

    @Test
    @DisplayName("if the claim expired while sending, success is not recorded twice and nothing breaks")
    void expiredClaimWhileSending() {
        claimWorks(1L, notification(1L, 1));
        when(emailSender.channel()).thenReturn("SMTP");
        when(repository.markSent(1L, NOW, "SMTP")).thenReturn(0);

        assertThat(dispatcher.processOne(1L)).isTrue();
        verify(repository, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("an invitation gets a fresh one-time link at send time, in both the text and the HTML part")
    void invitationLinkIsCreatedWhenSending() {
        Notification invitation = new Notification("new@example.com", NotificationType.EMPLOYEE_INVITED, null, "EMPLOYEE_INVITED:5",
                "Welcome", "<a href=\"" + NotificationService.SETUP_LINK_PLACEHOLDER + "\">Go</a>",
                "Open: " + NotificationService.SETUP_LINK_PLACEHOLDER, NOW).aboutUser(5L);
        ReflectionTestUtils.setField(invitation, "id", 1L);
        ReflectionTestUtils.setField(invitation, "attempts", 1);
        claimWorks(1L, invitation);
        when(emailSender.channel()).thenReturn("SMTP");
        when(accountTokens.issueInvitationLink(5L)).thenReturn("https://wt.example/setup-password.html#token=abc123");

        dispatcher.processOne(1L);

        ArgumentCaptor<OutboundEmail> sent = ArgumentCaptor.forClass(OutboundEmail.class);
        verify(emailSender).send(sent.capture());
        assertThat(sent.getValue().text()).isEqualTo("Open: https://wt.example/setup-password.html#token=abc123");
        assertThat(sent.getValue().html()).contains("href=\"https://wt.example/setup-password.html#token=abc123\"")
                .doesNotContain(NotificationService.SETUP_LINK_PLACEHOLDER);
    }

    @Test
    @DisplayName("if the invited account is gone or inactive the invitation fails at once and nothing is sent")
    void invitationForUnavailableAccountFailsPermanently() {
        Notification invitation = new Notification("new@example.com", NotificationType.EMPLOYEE_INVITED, null, "EMPLOYEE_INVITED:5",
                "Welcome", "<p>x</p>", NotificationService.SETUP_LINK_PLACEHOLDER, NOW).aboutUser(5L);
        ReflectionTestUtils.setField(invitation, "id", 1L);
        ReflectionTestUtils.setField(invitation, "attempts", 1);
        claimWorks(1L, invitation);
        when(accountTokens.issueInvitationLink(5L))
                .thenThrow(new EmailDeliveryException("The account no longer exists or is not active", true, null));

        dispatcher.processOne(1L);

        verify(emailSender, never()).send(any());
        verify(repository).markFailed(eq(1L), anyString());
    }
}
