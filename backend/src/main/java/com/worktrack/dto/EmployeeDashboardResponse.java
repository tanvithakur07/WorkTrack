package com.worktrack.dto;

import java.util.List;

public record EmployeeDashboardResponse(
        EmployeeResponse profile,
        long totalRequests,
        long pendingRequests,
        long approvedRequests,
        long rejectedRequests,
        long cancelledRequests,
        long approvedDaysThisYear,
        List<LeaveResponse> recentRequests) {
}
