package com.worktrack.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Team picture for a period and one selected day. Reported hours are what employees typed in; they are
 * not verified measurements. Drafts are private to their author and never counted.
 *
 * Coverage = people who submitted at least one report on a weekday / active EMPLOYEE accounts. It uses
 * today's headcount (not historical), does not subtract approved leave and does not know public holidays,
 * so it can read lower than reality on such days. The missing-reports list (which does subtract leave)
 * stays the precise tool for "who is missing".
 */
public record AdminAnalyticsResponse(
        LocalDate from,
        LocalDate to,
        long activeEmployees,
        long expectedReporters,
        long pendingLeaveRequests,
        BigDecimal reportedHours,
        long entryCount,
        Map<String, Long> tasksByStatus,
        DaySnapshot selectedDay,
        Double averageCoveragePercent,
        List<TrendPoint> trend,
        List<WorkLogResponse> recentReports,
        List<LeaveResponse> recentLeaveActivity) {

    /** What was submitted on one day. coveragePercent is null on weekends or when nobody is expected to report. */
    public record DaySnapshot(LocalDate date, long entries, long employeesReporting, long expectedReporters,
                              BigDecimal hours, Double coveragePercent, boolean weekend) {
    }

    /** One calendar day. coveragePercent is null for weekends, future days and when nobody is expected. */
    public record TrendPoint(LocalDate date, BigDecimal hours, long entries, long employeesReporting,
                             Double coveragePercent, boolean weekend) {
    }
}
