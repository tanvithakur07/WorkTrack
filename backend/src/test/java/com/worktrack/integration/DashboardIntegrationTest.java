package com.worktrack.integration;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveType;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;

class DashboardIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("admin dashboard numbers are computed from the database")
    void adminNumbersMatchDatabase() throws Exception {
        User carol = createUser("Carol Gone", "carol@example.com", Role.EMPLOYEE, "Sales");
        carol.setStatus(EmploymentStatus.INACTIVE);
        userRepository.save(carol);

        LocalDate monday = futureMonday();
        saveLeave(alice, LeaveType.CASUAL, monday, monday.plusDays(1));                // pending
        saveLeave(bob, LeaveType.CASUAL, monday, monday.plusDays(1));                  // pending
        LeaveRequest approved = saveLeave(alice, LeaveType.EARNED, monday.plusDays(14), monday.plusDays(16));
        approved.approve(admin, Instant.now());
        leaveRepository.save(approved);
        LeaveRequest rejected = saveLeave(bob, LeaveType.SICK, monday.plusDays(21), monday.plusDays(22));
        rejected.reject(admin, Instant.now(), "Not enough notice");
        leaveRepository.save(rejected);

        mockMvc.perform(get("/api/dashboard/admin").session(login(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEmployees").value(4))
                .andExpect(jsonPath("$.activeEmployees").value(3))
                .andExpect(jsonPath("$.pendingLeaves").value(2))
                .andExpect(jsonPath("$.approvedLeaves").value(1))
                .andExpect(jsonPath("$.rejectedLeaves").value(1))
                .andExpect(jsonPath("$.pendingQueue", hasSize(2)));
    }

    @Test
    @DisplayName("the admin dashboard is forbidden to employees")
    void adminDashboardIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/dashboard/admin").session(login(alice))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an employee's dashboard counts only their own requests and shows only their own profile")
    void employeeDashboardIsPersonal() throws Exception {
        LocalDate monday = futureMonday();
        saveLeave(alice, LeaveType.CASUAL, monday, monday.plusDays(1));
        LeaveRequest approved = saveLeave(alice, LeaveType.EARNED, monday.plusDays(14), monday.plusDays(16));
        approved.approve(admin, Instant.now());
        leaveRepository.save(approved);
        saveLeave(bob, LeaveType.CASUAL, monday, monday.plusDays(1));                  // somebody else's

        mockMvc.perform(get("/api/dashboard/employee").session(login(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.email").value("alice@example.com"))
                .andExpect(jsonPath("$.totalRequests").value(2))
                .andExpect(jsonPath("$.pendingRequests").value(1))
                .andExpect(jsonPath("$.approvedRequests").value(1))
                .andExpect(jsonPath("$.rejectedRequests").value(0))
                .andExpect(jsonPath("$.recentRequests", hasSize(2)))
                .andExpect(jsonPath("$.recentRequests[0].employeeId").value(alice.getId()))
                .andExpect(jsonPath("$.recentRequests[1].employeeId").value(alice.getId()));
    }

    @Test
    @DisplayName("approved days this year clip requests that cross New Year and ignore weekends (both directions)")
    void approvedDaysClipAtYearBoundaries() throws Exception {
        int year = LocalDate.now().getYear();
        // Last year's Dec 29 to this year's Jan 3, and this year's Dec 27 to next year's Jan 4.
        LocalDate spanInStart = LocalDate.of(year - 1, 12, 29);
        LocalDate spanInEnd = LocalDate.of(year, 1, 3);
        LocalDate spanOutStart = LocalDate.of(year, 12, 27);
        LocalDate spanOutEnd = LocalDate.of(year + 1, 1, 4);

        LeaveRequest first = saveLeave(alice, LeaveType.EARNED, spanInStart, spanInEnd);
        first.approve(admin, Instant.now());
        leaveRepository.save(first);
        LeaveRequest second = saveLeave(alice, LeaveType.EARNED, spanOutStart, spanOutEnd);
        second.approve(admin, Instant.now());
        leaveRepository.save(second);
        // A pending request must not count at all.
        saveLeave(alice, LeaveType.CASUAL, LocalDate.of(year, 6, 1), LocalDate.of(year, 6, 5));

        long expected = weekdaysInYear(spanInStart, spanInEnd, year) + weekdaysInYear(spanOutStart, spanOutEnd, year);

        mockMvc.perform(get("/api/dashboard/employee").session(login(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedDaysThisYear").value(expected));
    }

    @Test
    @DisplayName("'away today' lists active employees only, but the leave record of a deactivated one is kept")
    void inactiveEmployeesAreNotAwayToday() throws Exception {
        LocalDate today = LocalDate.now();
        LeaveRequest activeLeave = saveLeaveCovering(alice, today);
        activeLeave.approve(admin, Instant.now());
        leaveRepository.save(activeLeave);

        LeaveRequest inactiveLeave = saveLeaveCovering(bob, today);
        inactiveLeave.approve(admin, Instant.now());
        leaveRepository.save(inactiveLeave);
        bob.setStatus(EmploymentStatus.INACTIVE);
        userRepository.save(bob);

        mockMvc.perform(get("/api/dashboard/admin").session(login(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onLeaveToday").value(1))
                .andExpect(jsonPath("$.outToday", hasSize(1)))
                .andExpect(jsonPath("$.outToday[0].employeeId").value(alice.getId()))
                // history is preserved: both approved requests still count in the totals
                .andExpect(jsonPath("$.approvedLeaves").value(2));
        org.assertj.core.api.Assertions.assertThat(leaveRepository.findById(inactiveLeave.getId())).isPresent();
    }

    private LeaveRequest saveLeaveCovering(User owner, LocalDate day) {
        return saveLeave(owner, LeaveType.CASUAL, day.minusDays(1), day.plusDays(1));
    }

    private static long weekdaysInYear(LocalDate start, LocalDate end, int year) {
        long count = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            boolean weekend = d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
            if (d.getYear() == year && !weekend) {
                count++;
            }
        }
        return count;
    }
}
