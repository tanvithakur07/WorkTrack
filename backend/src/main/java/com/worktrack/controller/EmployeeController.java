package com.worktrack.controller;

import java.net.URI;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;

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

import com.worktrack.dto.CreateEmployeeRequest;
import com.worktrack.dto.EmployeeResponse;
import com.worktrack.dto.PageResponse;
import com.worktrack.dto.UpdateEmployeeRequest;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.security.WorkTrackUserDetails;
import com.worktrack.service.EmployeeService;
import com.worktrack.util.PageRequests;

/** Admin-only, except GET /{id}, which the service restricts to "admin or yourself". */
@RestController
@RequestMapping("/api/employees")
@PreAuthorize("hasRole('ADMIN')")
public class EmployeeController {

    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "fullName", "email", "department", "jobTitle", "joiningDate", "status", "createdAt");

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @GetMapping
    public PageResponse<EmployeeResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) EmploymentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "fullName") String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {
        return employeeService.search(q, department, status,
                PageRequests.of(page, size, sortBy, direction, SORT_FIELDS));
    }

    @GetMapping("/departments")
    public List<String> departments() {
        return employeeService.departments();
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public EmployeeResponse get(@PathVariable Long id, @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return employeeService.getById(id, principal.toCurrentUser());
    }

    @PostMapping
    public ResponseEntity<EmployeeResponse> create(@Valid @RequestBody CreateEmployeeRequest request) {
        EmployeeResponse created = employeeService.create(request);
        return ResponseEntity.created(URI.create("/api/employees/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public EmployeeResponse update(@PathVariable Long id,
                                   @Valid @RequestBody UpdateEmployeeRequest request,
                                   @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return employeeService.update(id, request, principal.toCurrentUser());
    }

    /** Sends the invitation e-mail again (for example after the link expired). Admin only. */
    @PostMapping("/{id}/invitation")
    public EmployeeResponse resendInvitation(@PathVariable Long id,
                                             @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return employeeService.resendInvitation(id, principal.toCurrentUser());
    }

    /** Deactivates the employee (soft delete). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id,
                                           @AuthenticationPrincipal WorkTrackUserDetails principal) {
        employeeService.deactivate(id, principal.toCurrentUser());
        return ResponseEntity.noContent().build();
    }
}
