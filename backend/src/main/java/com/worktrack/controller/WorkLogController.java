package com.worktrack.controller;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.worktrack.dto.MissingSubmissionResponse;
import com.worktrack.dto.PageResponse;
import com.worktrack.dto.TeamWorkSummaryResponse;
import com.worktrack.dto.WorkLogRequest;
import com.worktrack.dto.WorkLogResponse;
import com.worktrack.dto.WorkLogSummaryResponse;
import com.worktrack.entity.WorkLogStatus;
import com.worktrack.security.WorkTrackUserDetails;
import com.worktrack.service.WorkLogService;
import com.worktrack.util.PageRequests;

/**
 * Daily work reports. The employee is always taken from the session ({@code principal}); there is
 * no employee-id parameter on any endpoint that creates or lists "my" entries.
 */
@RestController
@RequestMapping("/api/work-logs")
public class WorkLogController {

    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "workDate", "hoursSpent", "status", "taskTitle", "createdAt");

    private final WorkLogService workLogService;

    public WorkLogController(WorkLogService workLogService) {
        this.workLogService = workLogService;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<WorkLogResponse> create(@Valid @RequestBody WorkLogRequest request,
                                                  @AuthenticationPrincipal WorkTrackUserDetails principal) {
        WorkLogResponse created = workLogService.create(principal.toCurrentUser(), request);
        return ResponseEntity.created(URI.create("/api/work-logs/" + created.id())).body(created);
    }

    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    public PageResponse<WorkLogResponse> myLogs(
            @RequestParam(required = false) WorkLogStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "workDate") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return workLogService.search(principal.getId(), status, from, to, q, true,
                PageRequests.of(page, size, sortBy, direction, SORT_FIELDS));
    }

    @GetMapping("/my/summary")
    @PreAuthorize("isAuthenticated()")
    public WorkLogSummaryResponse mySummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return workLogService.mySummary(principal.toCurrentUser(), from, to);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<WorkLogResponse> allLogs(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) WorkLogStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "workDate") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        return workLogService.search(employeeId, status, from, to, q, false,
                PageRequests.of(page, size, sortBy, direction, SORT_FIELDS));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public TeamWorkSummaryResponse teamSummary(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return workLogService.teamSummary(from, to, employeeId);
    }

    @GetMapping("/missing")
    @PreAuthorize("hasRole('ADMIN')")
    public List<MissingSubmissionResponse> missing(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return workLogService.missingSubmissions(from, to, employeeId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public WorkLogResponse get(@PathVariable Long id, @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return workLogService.getById(id, principal.toCurrentUser());
    }

    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public WorkLogResponse update(@PathVariable Long id, @Valid @RequestBody WorkLogRequest request,
                                  @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return workLogService.update(id, principal.toCurrentUser(), request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal WorkTrackUserDetails principal) {
        workLogService.delete(id, principal.toCurrentUser());
        return ResponseEntity.noContent().build();
    }
}
