package com.worktrack.dto;

import java.time.LocalDate;
import java.util.List;

/** One employee and the working days in the range for which they have no submitted report. */
public record MissingSubmissionResponse(
        Long employeeId,
        String employeeName,
        String employeeEmail,
        String department,
        int missingCount,
        List<LocalDate> missingDates) {
}
