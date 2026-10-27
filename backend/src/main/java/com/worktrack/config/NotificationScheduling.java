package com.worktrack.config;

import com.worktrack.service.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the notification worker in the background, polling the table every few seconds. A single
 * scheduler thread runs one batch at a time per application instance; several instances are safe because
 * each notification is claimed with a guarded UPDATE.
 *
 * Switched off in tests (worktrack.notifications.worker.enabled=false) so they control exactly when
 * a batch runs.
 *
 * Restart recovery: a notification left PROCESSING by a process that died is picked up again as soon as its
 * lease (default 2 minutes) has expired. The first batch runs right at startup, so with a quick restart the
 * remaining wait is at most the lease time.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "worktrack.notifications.worker.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationScheduling {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduling.class);

    private final NotificationDispatcher dispatcher;

    public NotificationScheduling(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${worktrack.notifications.poll-ms:5000}", initialDelayString = "${worktrack.notifications.initial-delay-ms:5000}")
    public void tick() {
        try {
            dispatcher.processDue();
        } catch (RuntimeException problem) {
            // never let one bad cycle kill the scheduler thread
            log.error("Notification worker cycle failed; it will try again", problem);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        tick();
    }
}
