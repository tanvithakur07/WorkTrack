package com.worktrack.controller;

import com.worktrack.dto.AuditEventResponse;
import com.worktrack.dto.PageResponse;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.service.AuditActions;
import com.worktrack.service.AuditService;
import com.worktrack.util.PageRequests;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only audit history for administrators. There is deliberately no POST, PUT, PATCH or DELETE here:
 * audit lines are written only by the server, as a side effect of the action they describe.
 * (The URL rule in SecurityConfig and @PreAuthorize both require ADMIN.)
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final Set<String> SORT_FIELDS = Set.of("id", "occurredAt", "action", "outcome", "entityType");

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<AuditEventResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "occurredAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        return auditService.search(from, to, actorId, action, entityType, entityId, outcome,
                PageRequests.of(page, size, sortBy, direction, SORT_FIELDS));
    }

    /** The action names the filter accepts, so the page does not hard-code them. */
    @GetMapping("/actions")
    @PreAuthorize("hasRole('ADMIN')")
    public List<String> actions() {
        return AuditActions.ALL;
    }
}
