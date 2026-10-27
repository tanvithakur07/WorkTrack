package com.worktrack.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LeaveDayCalculatorTest {

    // 2026-03-09 is a Monday
    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 9);

    @Test
    @DisplayName("a Monday-to-Friday week is 5 leave days")
    void fullWorkingWeek() {
        assertThat(LeaveDayCalculator.countWorkingDays(MONDAY, MONDAY.plusDays(4))).isEqualTo(5);
    }

    @Test
    @DisplayName("weekends in the middle of a range are not counted")
    void weekendInsideRangeIsSkipped() {
        // Friday 13th to Monday 16th: Friday + Monday
        assertThat(LeaveDayCalculator.countWorkingDays(MONDAY.plusDays(4), MONDAY.plusDays(7))).isEqualTo(2);
    }

    @Test
    @DisplayName("a range that is only a weekend is 0 leave days")
    void weekendOnly() {
        assertThat(LeaveDayCalculator.countWorkingDays(MONDAY.plusDays(5), MONDAY.plusDays(6))).isZero();
    }

    @Test
    @DisplayName("a single working day is 1 and a single Saturday is 0")
    void singleDays() {
        assertThat(LeaveDayCalculator.countWorkingDays(MONDAY, MONDAY)).isEqualTo(1);
        assertThat(LeaveDayCalculator.countWorkingDays(MONDAY.plusDays(5), MONDAY.plusDays(5))).isZero();
    }

    @Test
    @DisplayName("two full weeks are 10 leave days")
    void twoWeeks() {
        assertThat(LeaveDayCalculator.countWorkingDays(MONDAY, MONDAY.plusDays(13))).isEqualTo(10);
    }

    @Test
    @DisplayName("an end date before the start date is rejected")
    void endBeforeStart() {
        assertThatThrownBy(() -> LeaveDayCalculator.countWorkingDays(MONDAY, MONDAY.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- annual totals: clipping a request to a calendar year -----------------------------------

    private static final LocalDate Y2026_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate Y2026_END = LocalDate.of(2026, 12, 31);

    @Test
    @DisplayName("December-to-January request: only the January days count for the new year")
    void decemberIntoJanuaryCountsOnlyNewYearDays() {
        // Mon 29 Dec 2025 to Fri 2 Jan 2026: 5 working days in total, Thu 1 Jan and Fri 2 Jan are in 2026
        LocalDate start = LocalDate.of(2025, 12, 29);
        LocalDate end = LocalDate.of(2026, 1, 2);
        assertThat(LeaveDayCalculator.countWorkingDaysWithin(start, end, Y2026_START, Y2026_END)).isEqualTo(2);
        assertThat(LeaveDayCalculator.countWorkingDaysWithin(start, end,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))).isEqualTo(3);
    }

    @Test
    @DisplayName("December-to-January request: the old year gets the December days, the new year the rest")
    void yearsSplitTheRequestWithoutLosingOrDoublingDays() {
        LocalDate start = LocalDate.of(2026, 12, 28);   // Monday
        LocalDate end = LocalDate.of(2027, 1, 1);       // Friday
        int in2026 = LeaveDayCalculator.countWorkingDaysWithin(start, end, Y2026_START, Y2026_END);
        int in2027 = LeaveDayCalculator.countWorkingDaysWithin(start, end,
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));
        assertThat(in2026).isEqualTo(4);
        assertThat(in2027).isEqualTo(1);
        assertThat(in2026 + in2027).isEqualTo(LeaveDayCalculator.countWorkingDays(start, end));
    }

    @Test
    @DisplayName("a request that started in the previous year counts only the days inside this year")
    void startedInPreviousYear() {
        assertThat(LeaveDayCalculator.countWorkingDaysWithin(
                LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 2), Y2026_START, Y2026_END)).isEqualTo(2);
    }

    @Test
    @DisplayName("weekend days inside the year are not counted, and a request outside the year counts 0")
    void weekendsAndOutsideRequests() {
        assertThat(LeaveDayCalculator.countWorkingDaysWithin(
                LocalDate.of(2022, 12, 31), LocalDate.of(2023, 1, 1),
                LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31))).isZero();   // Sat+Sun
        assertThat(LeaveDayCalculator.countWorkingDaysWithin(
                LocalDate.of(2024, 5, 6), LocalDate.of(2024, 5, 8), Y2026_START, Y2026_END)).isZero();
    }

    @Test
    @DisplayName("a single day on the last day of the window counts, and invalid bounds are rejected")
    void windowEdgesAndInvalidBounds() {
        LocalDate lastDay = LocalDate.of(2026, 12, 31);   // Thursday
        assertThat(LeaveDayCalculator.countWorkingDaysWithin(lastDay, lastDay, Y2026_START, Y2026_END)).isEqualTo(1);
        assertThatThrownBy(() -> LeaveDayCalculator.countWorkingDaysWithin(
                MONDAY, MONDAY.minusDays(1), Y2026_START, Y2026_END)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LeaveDayCalculator.countWorkingDaysWithin(
                MONDAY, MONDAY, Y2026_END, Y2026_START)).isInstanceOf(IllegalArgumentException.class);
    }
}
