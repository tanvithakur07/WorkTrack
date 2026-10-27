package com.worktrack.controller;

import com.worktrack.dto.NotificationResponse;
import com.worktrack.dto.NotificationSummaryResponse;
import com.worktrack.dto.PageResponse;
import com.worktrack.entity.NotificationStatus;
import com.worktrack.entity.NotificationType;
import com.worktrack.security.WorkTrackUserDetails;
import com.worktrack.mail.MailStatus;
import com.worktrack.service.NotificationService;
import com.worktrack.util.PageRequests;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The e-mail outbox, for administrators: inspect what was sent or failed, and re-queue a failed one. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final Set<String> SORT_FIELDS = Set.of("id", "createdAt", "status", "attempts", "lastAttemptAt", "sentAt");

    private final NotificationService notificationService;
    private final MailStatus mailStatus;

    public NotificationController(NotificationService notificationService, MailStatus mailStatus) {
        this.notificationService = notificationService;
        this.mailStatus = mailStatus;
    }

    /** How e-mail is configured (log or smtp, host, sender, warnings). Never includes credentials. */
    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public MailStatus.Snapshot status() {
        return mailStatus.snapshot();
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<NotificationResponse> list(
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        return notificationService.search(status, type, PageRequests.of(page, size, sortBy, direction, SORT_FIELDS));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public NotificationSummaryResponse summary() {
        return notificationService.summary();
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public NotificationResponse retry(@PathVariable Long id, @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return notificationService.retry(id, principal.toCurrentUser());
    }
}
