package com.worktrack.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Admin summary cards for a date range (submitted entries only, drafts excluded from every figure). */
public record TeamWorkSummaryResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal totalHours,
        long entryCount,
        long employeesReporting,
        long activeEmployees,
        Map<String, Long> statusCounts,
        List<EmployeeTotal> employees) {

    public record EmployeeTotal(Long employeeId, String employeeName, String department,
                                BigDecimal hours, long entries) {
    }
}
