package com.worktrack.dto;

import java.util.List;

public record AdminDashboardResponse(
        long totalEmployees,
        long activeEmployees,
        long pendingLeaves,
        long approvedLeaves,
        long rejectedLeaves,
        long cancelledLeaves,
        long onLeaveToday,
        List<DepartmentCountResponse> departments,
        List<LeaveResponse> pendingQueue,
        List<LeaveResponse> outToday) {
}
