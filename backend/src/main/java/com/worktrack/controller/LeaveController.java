package com.worktrack.controller;

import java.net.URI;
import java.time.LocalDate;
import java.util.Set;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.worktrack.dto.LeaveCreateRequest;
import com.worktrack.dto.LeaveRejectRequest;
import com.worktrack.dto.LeaveResponse;
import com.worktrack.dto.PageResponse;
import com.worktrack.entity.LeaveStatus;
import com.worktrack.security.WorkTrackUserDetails;
import com.worktrack.service.LeaveService;
import com.worktrack.util.PageRequests;

@RestController
@RequestMapping("/api/leaves")
public class LeaveController {

    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "startDate", "endDate", "days", "status", "leaveType", "createdAt");

    private final LeaveService leaveService;

    public LeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<LeaveResponse> submit(@Valid @RequestBody LeaveCreateRequest request,
                                                @AuthenticationPrincipal WorkTrackUserDetails principal) {
        LeaveResponse created = leaveService.submit(principal.toCurrentUser(), request);
        return ResponseEntity.created(URI.create("/api/leaves/" + created.id())).body(created);
    }

    /** The caller's own requests, always scoped to the signed-in user (there is no employee id parameter). */
    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    public PageResponse<LeaveResponse> myLeaves(
            @RequestParam(required = false) LeaveStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return leaveService.search(principal.getId(), status, from, to,
                PageRequests.of(page, size, sortBy, direction, SORT_FIELDS));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<LeaveResponse> allLeaves(
            @RequestParam(required = false) LeaveStatus status,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        return leaveService.search(employeeId, status, from, to,
                PageRequests.of(page, size, sortBy, direction, SORT_FIELDS));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public LeaveResponse get(@PathVariable Long id, @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return leaveService.getById(id, principal.toCurrentUser());
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public LeaveResponse cancel(@PathVariable Long id, @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return leaveService.cancel(id, principal.toCurrentUser());
    }

    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public LeaveResponse approve(@PathVariable Long id, @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return leaveService.approve(id, principal.toCurrentUser());
    }

    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public LeaveResponse reject(@PathVariable Long id,
                                @Valid @RequestBody LeaveRejectRequest request,
                                @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return leaveService.reject(id, request.reason(), principal.toCurrentUser());
    }
}
