package com.worktrack.service;

import com.worktrack.entity.Notification;
import com.worktrack.entity.NotificationType;
import com.worktrack.mail.EmailTemplates;
import com.worktrack.mail.Backoff;
import com.worktrack.mail.EmailDeliveryException;
import com.worktrack.mail.EmailSender;
import com.worktrack.mail.ErrorSanitizer;
import com.worktrack.mail.OutboundEmail;
import com.worktrack.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Sends queued e-mails. It is called by the scheduled worker (and directly by tests).
 *
 * How one notification is handled:
 *  1. CLAIM it with one guarded UPDATE (PENDING and due, or PROCESSING with an expired lease). Only one
 *     caller can win, so two workers (or two application instances) never send the same row at once.
 *  2. SEND with no database transaction open, because an SMTP call can take many seconds.
 *  3. Record the result with another guarded UPDATE:
 *       accepted by the SMTP server -> SENT
 *       temporary failure           -> PENDING again after an exponentially longer delay
 *       permanent failure, or no attempts left -> FAILED
 *
 * Guarantees and limits, stated plainly:
 *  - SENT means the SMTP server accepted the message. It does not prove the recipient received or read it.
 *  - Delivery is "at least once". If the process dies after the server accepted the message but before
 *    SENT was saved, the lease expires and the e-mail is sent again. A duplicate is possible in that rare
 *    window; a lost e-mail is not (until FAILED).
 *  - The lease must be longer than the longest possible send (SMTP timeouts are 10 s each), otherwise a
 *    slow send could be taken over while it is still running.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationRepository repository;
    private final EmailSender emailSender;
    private final NotificationProperties properties;
    private final Clock clock;
    private final AccountTokenService accountTokens;
    private final String[] secrets;

    public NotificationDispatcher(NotificationRepository repository, EmailSender emailSender,
                                  NotificationProperties properties, Clock clock, AccountTokenService accountTokens,
                                  @Value("${spring.mail.username:}") String mailUsername,
                                  @Value("${spring.mail.password:}") String mailPassword) {
        this.repository = repository;
        this.emailSender = emailSender;
        this.properties = properties;
        this.clock = clock;
        this.accountTokens = accountTokens;
        this.secrets = new String[]{mailUsername, mailPassword};
    }

    /** Processes up to one batch of due notifications. Returns how many this call actually handled. */
    public int processDue() {
        Instant now = clock.instant();
        List<Long> due = repository.findDueIds(now, PageRequest.of(0, properties.batchSize()));
        int handled = 0;
        for (Long id : due) {
            if (processOne(id)) {
                handled++;
            }
        }
        return handled;
    }

    /** @return true if this call claimed and handled the notification (whatever the outcome) */
    boolean processOne(Long id) {
        Instant now = clock.instant();
        if (repository.claim(id, now, now.plus(properties.lease())) == 0) {
            return false; // somebody else has it, or it is no longer due
        }
        Notification notification = repository.findById(id).orElse(null);
        if (notification == null) {
            return false;
        }
        int attempt = notification.getAttempts();
        try {
            emailSender.send(buildEmail(notification));
        } catch (EmailDeliveryException failure) {
            recordFailure(notification, attempt, failure.isPermanent(), failure);
            return true;
        } catch (RuntimeException unexpected) {
            recordFailure(notification, attempt, false, unexpected);
            return true;
        }
        if (repository.markSent(id, clock.instant(), emailSender.channel()) == 0) {
            // Our lease expired while sending and another worker took over: they will record the outcome.
            log.warn("Notification {} was sent but its claim had expired; another worker owns it now", id);
        } else {
            log.info("Notification {} ({}) accepted by the mail system via {} on attempt {}",
                    id, notification.getType(), emailSender.channel(), attempt);
        }
        return true;
    }

    /**
     * The stored message, except that an invitation gets its one-time link now. The link is created only at
     * send time, so it never sits in the outbox table; a retry creates a new link that replaces the earlier one.
     */
    private OutboundEmail buildEmail(Notification notification) {
        String html = notification.getBodyHtml();
        String text = notification.getBodyText();
        if (notification.getType() == NotificationType.EMPLOYEE_INVITED) {
            String link = accountTokens.issueInvitationLink(notification.getSubjectUserId());
            html = html.replace(NotificationService.SETUP_LINK_PLACEHOLDER, EmailTemplates.esc(link));
            text = text.replace(NotificationService.SETUP_LINK_PLACEHOLDER, link);
        }
        return new OutboundEmail(notification.getRecipientEmail(), notification.getSubject(), html, text);
    }

    private void recordFailure(Notification notification, int attempt, boolean permanent, RuntimeException failure) {
        String error = ErrorSanitizer.sanitize(failure, secrets);
        Long id = notification.getId();
        if (permanent || attempt >= properties.maxAttempts()) {
            repository.markFailed(id, error);
            log.warn("Notification {} ({}) FAILED after {} attempt(s){}: {}", id, notification.getType(), attempt,
                    permanent ? " (permanent error)" : " (attempts used up)", error);
        } else {
            Instant next = clock.instant().plus(Backoff.delayAfter(attempt, properties.baseDelay(), properties.maxDelay()));
            repository.markRetry(id, next, error);
            log.warn("Notification {} ({}) attempt {} failed, will retry at {}: {}", id, notification.getType(),
                    attempt, next, error);
        }
    }
}
