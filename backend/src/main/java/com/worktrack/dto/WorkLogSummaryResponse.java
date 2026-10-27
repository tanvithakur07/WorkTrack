package com.worktrack.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Totals for a date range. Hours and day totals count submitted entries only (everything except
 * DRAFT); statusCounts shows every entry, drafts included.
 */
public record WorkLogSummaryResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal totalHours,
        long entryCount,
        long daysReported,
        long draftCount,
        Map<String, Long> statusCounts,
        List<DayTotal> days) {

    public record DayTotal(LocalDate date, BigDecimal hours, long entries) {
    }
}
