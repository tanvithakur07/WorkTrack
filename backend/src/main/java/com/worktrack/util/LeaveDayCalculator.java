package com.worktrack.util;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Leave-day policy: weekends (Saturday and Sunday) are NOT counted.
 * Public holidays are not modelled; they count as working days.
 */
public final class LeaveDayCalculator {

    private LeaveDayCalculator() {
    }

    /** Number of Monday-to-Friday days between start and end, both inclusive. */
    public static int countWorkingDays(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end must not be before start");
        }
        int days = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            DayOfWeek dayOfWeek = day.getDayOfWeek();
            if (dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days;
    }

    /**
     * Monday-to-Friday days of the range [start, end] that fall inside the window
     * [windowStart, windowEnd], all bounds inclusive. The range is clipped to the window first,
     * so a request from 29 Dec to 2 Jan counts only its January days for the new year and only
     * its December days for the old one. Returns 0 when the range and the window do not touch.
     */
    public static int countWorkingDaysWithin(LocalDate start, LocalDate end,
                                             LocalDate windowStart, LocalDate windowEnd) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end must not be before start");
        }
        if (windowEnd.isBefore(windowStart)) {
            throw new IllegalArgumentException("windowEnd must not be before windowStart");
        }
        LocalDate from = start.isBefore(windowStart) ? windowStart : start;
        LocalDate to = end.isAfter(windowEnd) ? windowEnd : end;
        if (to.isBefore(from)) {
            return 0;
        }
        return countWorkingDays(from, to);
    }
}
