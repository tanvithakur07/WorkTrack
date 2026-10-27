package com.worktrack.controller;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;
import com.worktrack.dto.AdminAnalyticsResponse;
import com.worktrack.dto.EmployeeAnalyticsResponse;
import com.worktrack.service.AnalyticsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.worktrack.dto.AdminDashboardResponse;
import com.worktrack.dto.EmployeeDashboardResponse;
import com.worktrack.security.WorkTrackUserDetails;
import com.worktrack.service.DashboardService;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final AnalyticsService analyticsService;

    public DashboardController(DashboardService dashboardService, AnalyticsService analyticsService) {
        this.dashboardService = dashboardService;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminDashboardResponse admin() {
        return dashboardService.adminDashboard();
    }

    @GetMapping("/employee")
    @PreAuthorize("isAuthenticated()")
    public EmployeeDashboardResponse employee(@AuthenticationPrincipal WorkTrackUserDetails principal) {
        return dashboardService.employeeDashboard(principal.toCurrentUser());
    }

    /** The employee's own work-hours picture. There is no employee-id parameter: it is always the caller. */
    @GetMapping("/employee/analytics")
    @PreAuthorize("isAuthenticated()")
    public EmployeeAnalyticsResponse employeeAnalytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal WorkTrackUserDetails principal) {
        return analyticsService.employee(principal.toCurrentUser(), from, to);
    }

    @GetMapping("/admin/analytics")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminAnalyticsResponse adminAnalytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return analyticsService.admin(from, to, day);
    }
}
