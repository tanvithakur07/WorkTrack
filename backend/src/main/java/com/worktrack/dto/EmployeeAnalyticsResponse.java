package com.worktrack.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The employee's own productivity picture for a period. Hours are what the employee REPORTED; they are
 * not verified measurements. Totals count submitted entries only (drafts excluded), while
 * tasksByStatus also counts drafts so the person sees their whole list.
 */
public record EmployeeAnalyticsResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal reportedHours,
        long entryCount,
        long daysReported,
        long completedTasks,
        long inProgressTasks,
        Map<String, Long> tasksByStatus,
        List<WorkLogSummaryResponse.DayTotal> hoursByDay,
        List<WorkLogResponse> recentEntries) {
}
