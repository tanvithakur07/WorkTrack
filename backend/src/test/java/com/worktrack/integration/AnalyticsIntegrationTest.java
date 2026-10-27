package com.worktrack.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.worktrack.entity.DailyWorkLog;
import com.worktrack.entity.User;
import com.worktrack.entity.WorkLogStatus;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnalyticsIntegrationTest extends IntegrationTestBase {

    private static LocalDate lastWeekday() {
        LocalDate d = LocalDate.now().minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private void log(User owner, LocalDate date, String hours, WorkLogStatus status) {
        workLogRepository.save(new DailyWorkLog(owner, date, "Task", "Details", new BigDecimal(hours), status));
    }

    @Test
    @DisplayName("employee analytics count only the caller's submitted reports; drafts and other people's work are excluded")
    void employeeIsolation() throws Exception {
        LocalDate day = lastWeekday();
        log(alice, day, "3.00", WorkLogStatus.COMPLETED);
        log(alice, day, "1.50", WorkLogStatus.IN_PROGRESS);
        log(alice, day, "4.00", WorkLogStatus.DRAFT);
        log(bob, day, "8.00", WorkLogStatus.COMPLETED);

        mockMvc.perform(get("/api/dashboard/employee/analytics").session(login(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportedHours").value(4.5))
                .andExpect(jsonPath("$.entryCount").value(2))
                .andExpect(jsonPath("$.daysReported").value(1))
                .andExpect(jsonPath("$.completedTasks").value(1))
                .andExpect(jsonPath("$.inProgressTasks").value(1))
                .andExpect(jsonPath("$.tasksByStatus.BLOCKED").value(0));
    }

    @Test
    @DisplayName("admin analytics: totals across employees, drafts excluded, a zero-filled trend, and coverage")
    void adminTotals() throws Exception {
        LocalDate day = lastWeekday();
        log(alice, day, "3.00", WorkLogStatus.COMPLETED);
        log(bob, day, "5.00", WorkLogStatus.BLOCKED);
        log(bob, day, "9.00", WorkLogStatus.DRAFT);

        mockMvc.perform(get("/api/dashboard/admin/analytics?from=" + day.minusDays(2) + "&to=" + day + "&day=" + day).session(login(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportedHours").value(8.0))
                .andExpect(jsonPath("$.entryCount").value(2))
                .andExpect(jsonPath("$.expectedReporters").value(2))
                .andExpect(jsonPath("$.trend", hasSize(3)))
                .andExpect(jsonPath("$.tasksByStatus.DRAFT").doesNotExist())
                .andExpect(jsonPath("$.tasksByStatus.BLOCKED").value(1))
                .andExpect(jsonPath("$.selectedDay.employeesReporting").value(2))
                .andExpect(jsonPath("$.selectedDay.coveragePercent").value(100.0));
    }

    @Test
    @DisplayName("admin analytics are admin-only; employees get 403, anonymous 401")
    void adminOnly() throws Exception {
        mockMvc.perform(get("/api/dashboard/admin/analytics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dashboard/admin/analytics").session(login(alice))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("invalid ranges are a 400 for both roles")
    void invalidRanges() throws Exception {
        mockMvc.perform(get("/api/dashboard/admin/analytics?from=2026-03-05&to=2026-03-01").session(login(admin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/dashboard/admin/analytics?from=2020-01-01&to=2026-03-01").session(login(admin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/dashboard/employee/analytics?from=2020-01-01&to=2026-03-01").session(login(alice)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/dashboard/employee/analytics?from=not-a-date").session(login(alice)))
                .andExpect(status().isBadRequest());
    }
}
