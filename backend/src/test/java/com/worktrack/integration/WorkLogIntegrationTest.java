package com.worktrack.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.worktrack.entity.DailyWorkLog;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveType;
import com.worktrack.entity.User;
import com.worktrack.entity.WorkLogStatus;

/**
 * The daily work tracker end to end: real login session, real CSRF check, real security rules and a real
 * (H2) database. Dates are relative to the real today, so nothing here depends on the calendar.
 */
class WorkLogIntegrationTest extends IntegrationTestBase {

    private final LocalDate today = LocalDate.now();

    /* ------------------------------ helpers ------------------------------ */

    private Map<String, Object> body(LocalDate date, String title, String hours, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workDate", date.toString());
        body.put("taskTitle", title);
        body.put("description", "Details for " + title);
        body.put("hoursSpent", new BigDecimal(hours));
        body.put("status", status);
        return body;
    }

    private DailyWorkLog saveLog(User owner, LocalDate date, String title, String hours, WorkLogStatus status) {
        return workLogRepository.save(new DailyWorkLog(owner, date, title, "Details for " + title,
                new BigDecimal(hours), status));
    }

    private long create(MockHttpSession session, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(jsonPost("/api/work-logs", session, body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    /** The most recent weekdays strictly before today, newest first. */
    private List<LocalDate> recentWeekdays(int count) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = today.minusDays(1); days.size() < count; day = day.minusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(day);
            }
        }
        return days;
    }

    /* ------------------------------ creating ------------------------------ */

    @Test
    @DisplayName("an employee logs work: 201, owner is the signed-in user, several entries per day are fine")
    void createsEntries() throws Exception {
        MockHttpSession session = login(alice);

        mockMvc.perform(jsonPost("/api/work-logs", session, body(today, "Fix login bug", "3.5", "COMPLETED")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeId").value(alice.getId()))
                .andExpect(jsonPath("$.employeeName").value("Alice Employee"))
                .andExpect(jsonPath("$.hoursSpent").value(3.5))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        create(session, body(today, "Code review", "1", "IN_PROGRESS"));

        assertThat(workLogRepository.findAll()).hasSize(2)
                .allMatch(log -> log.getEmployee().getId().equals(alice.getId()));
    }

    @Test
    @DisplayName("an employee id in the body is ignored: the author always comes from the session")
    void ownerCannotBeChosen() throws Exception {
        MockHttpSession session = login(alice);
        Map<String, Object> body = body(today, "Sneaky entry", "2", "COMPLETED");
        body.put("employeeId", bob.getId());
        body.put("id", 9999);

        long id = create(session, body);

        DailyWorkLog saved = workLogRepository.findDetailedById(id).orElseThrow();
        assertThat(saved.getEmployee().getId()).isEqualTo(alice.getId());
        assertThat(saved.getId()).isNotEqualTo(9999L);
    }

    @Test
    @DisplayName("invalid input is a 400 with field messages, and nothing is stored")
    void validatesInput() throws Exception {
        MockHttpSession session = login(alice);

        Map<String, Object> empty = new LinkedHashMap<>();
        mockMvc.perform(jsonPost("/api/work-logs", session, empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.workDate").exists())
                .andExpect(jsonPath("$.fieldErrors.taskTitle").exists())
                .andExpect(jsonPath("$.fieldErrors.description").exists())
                .andExpect(jsonPath("$.fieldErrors.hoursSpent").exists())
                .andExpect(jsonPath("$.fieldErrors.status").exists());

        Map<String, Object> shortTitle = body(today, "ab", "1", "COMPLETED");
        mockMvc.perform(jsonPost("/api/work-logs", session, shortTitle))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.taskTitle").exists());
        for (String hours : List.of("0", "0.24", "-1", "24.5", "1.234")) {
            mockMvc.perform(jsonPost("/api/work-logs", session, body(today, "Hours check", hours, "COMPLETED")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.hoursSpent").exists());
        }
        Map<String, Object> longText = body(today, "Long text", "1", "COMPLETED");
        longText.put("description", "x".repeat(2001));
        mockMvc.perform(jsonPost("/api/work-logs", session, longText))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.description").exists());
        mockMvc.perform(jsonPost("/api/work-logs", session, body(today, "Bad status", "1", "FINISHED")))
                .andExpect(status().isBadRequest());

        assertThat(workLogRepository.count()).isZero();
    }

    @Test
    @DisplayName("future dates and dates older than 30 days are refused; 30 days back is allowed")
    void validatesDates() throws Exception {
        MockHttpSession session = login(alice);

        mockMvc.perform(jsonPost("/api/work-logs", session, body(today.plusDays(1), "Tomorrow", "1", "COMPLETED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("future")));
        mockMvc.perform(jsonPost("/api/work-logs", session, body(today.minusDays(31), "Too old", "1", "COMPLETED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("30 days")));
        mockMvc.perform(jsonPost("/api/work-logs", session, body(today.minusDays(30), "Edge of window", "1", "COMPLETED")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("one person cannot log more than 24 hours on a day; another person's hours do not count")
    void dayTotalIsCapped() throws Exception {
        MockHttpSession aliceSession = login(alice);
        create(aliceSession, body(today, "Long first task", "20", "COMPLETED"));

        mockMvc.perform(jsonPost("/api/work-logs", aliceSession, body(today, "Too much", "4.5", "COMPLETED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("24 hours")));
        mockMvc.perform(jsonPost("/api/work-logs", aliceSession, body(today, "Exactly full", "4", "COMPLETED")))
                .andExpect(status().isCreated());
        mockMvc.perform(jsonPost("/api/work-logs", login(bob), body(today, "Bob's day", "8", "COMPLETED")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("simultaneous entries cannot get around the 24-hour limit")
    void concurrentEntriesRespectTheCap() throws Exception {
        List<MockHttpSession> sessions = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            sessions.add(login(alice));
        }
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (MockHttpSession session : sessions) {
            tasks.add(() -> mockMvc.perform(jsonPost("/api/work-logs", session, body(today, "Parallel", "10", "COMPLETED")))
                    .andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = runTogether(tasks);

        assertThat(statuses.stream().filter(code -> code == 201).count()).isEqualTo(2);
        assertThat(statuses.stream().filter(code -> code == 400).count()).isEqualTo(2);
        assertThat(workLogRepository.findAll().stream()
                .map(DailyWorkLog::getHoursSpent).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("20");
    }

    /* ------------------------------ my entries ------------------------------ */

    @Test
    @DisplayName("my list contains only my own entries and supports status, date and text filters, paging and sorting")
    void myListIsScopedAndFilterable() throws Exception {
        saveLog(alice, today, "Alpha report", "2", WorkLogStatus.COMPLETED);
        saveLog(alice, today.minusDays(1), "Beta fix", "3", WorkLogStatus.BLOCKED);
        saveLog(alice, today.minusDays(2), "Gamma draft", "1", WorkLogStatus.DRAFT);
        saveLog(bob, today, "Bob's secret task", "5", WorkLogStatus.COMPLETED);
        MockHttpSession session = login(alice);

        mockMvc.perform(get("/api/work-logs/my").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].taskTitle").value("Alpha report"))
                .andExpect(jsonPath("$.content[*].employeeId").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is(alice.getId().intValue()))));
        mockMvc.perform(get("/api/work-logs/my").session(session).param("status", "BLOCKED"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].taskTitle").value("Beta fix"));
        mockMvc.perform(get("/api/work-logs/my").session(session).param("q", "gamma"))
                .andExpect(jsonPath("$.content", hasSize(1)));
        mockMvc.perform(get("/api/work-logs/my").session(session).param("q", "secret"))
                .andExpect(jsonPath("$.content", hasSize(0)));
        mockMvc.perform(get("/api/work-logs/my").session(session)
                        .param("from", today.minusDays(1).toString()).param("to", today.toString()))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/work-logs/my").session(session).param("size", "1").param("page", "1")
                        .param("sortBy", "hoursSpent").param("direction", "asc"))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].taskTitle").value("Alpha report"));
    }

    @Test
    @DisplayName("bad list parameters are a 400, a text search treats % and _ literally")
    void listParametersAreValidated() throws Exception {
        saveLog(alice, today, "Plain title", "1", WorkLogStatus.COMPLETED);
        saveLog(alice, today, "100% done", "1", WorkLogStatus.COMPLETED);
        MockHttpSession session = login(alice);

        mockMvc.perform(get("/api/work-logs/my").session(session).param("sortBy", "employee.passwordHash"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/work-logs/my").session(session).param("size", "1000"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/work-logs/my").session(session).param("status", "NOPE"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/work-logs/my").session(session)
                        .param("from", today.toString()).param("to", today.minusDays(1).toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/work-logs/my").session(session).param("q", "%"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].taskTitle").value("100% done"));
    }

    @Test
    @DisplayName("my summary counts submitted hours only, per day, with drafts reported separately")
    void mySummary() throws Exception {
        saveLog(alice, today, "Morning task", "3", WorkLogStatus.COMPLETED);
        saveLog(alice, today, "Afternoon task", "2.5", WorkLogStatus.IN_PROGRESS);
        saveLog(alice, today.minusDays(1), "Stuck task", "4", WorkLogStatus.BLOCKED);
        saveLog(alice, today.minusDays(1), "Unfinished note", "6", WorkLogStatus.DRAFT);
        saveLog(alice, today.minusDays(20), "Out of range", "8", WorkLogStatus.COMPLETED);
        saveLog(bob, today, "Someone else's task", "7", WorkLogStatus.COMPLETED);

        mockMvc.perform(get("/api/work-logs/my/summary").session(login(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHours").value(9.5))
                .andExpect(jsonPath("$.entryCount").value(3))
                .andExpect(jsonPath("$.daysReported").value(2))
                .andExpect(jsonPath("$.draftCount").value(1))
                .andExpect(jsonPath("$.statusCounts.COMPLETED").value(1))
                .andExpect(jsonPath("$.statusCounts.BLOCKED").value(1))
                .andExpect(jsonPath("$.days", hasSize(2)))
                .andExpect(jsonPath("$.days[1].date").value(today.toString()))
                .andExpect(jsonPath("$.days[1].hours").value(5.5));
        mockMvc.perform(get("/api/work-logs/my/summary").session(login(alice))
                        .param("from", today.minusDays(30).toString()).param("to", today.toString()))
                .andExpect(jsonPath("$.totalHours").value(17.5));
        mockMvc.perform(get("/api/work-logs/my/summary").session(login(alice))
                        .param("from", today.minusDays(400).toString()).param("to", today.toString()))
                .andExpect(status().isBadRequest());
    }

    /* ------------------------------ ownership ------------------------------ */

    @Test
    @DisplayName("another employee cannot read, edit or delete my entry (403) and nothing changes")
    void otherEmployeesCannotTouchMyEntries() throws Exception {
        DailyWorkLog draft = saveLog(alice, today, "Alice's draft", "2", WorkLogStatus.DRAFT);
        DailyWorkLog done = saveLog(alice, today, "Alice's work", "2", WorkLogStatus.COMPLETED);
        MockHttpSession bobSession = login(bob);

        mockMvc.perform(get("/api/work-logs/" + done.getId()).session(bobSession)).andExpect(status().isForbidden());
        mockMvc.perform(withCsrf(put("/api/work-logs/" + done.getId())).session(bobSession)
                        .contentType("application/json").content(json(body(today, "Hijacked", "9", "COMPLETED"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(withCsrf(delete("/api/work-logs/" + draft.getId())).session(bobSession))
                .andExpect(status().isForbidden());

        assertThat(workLogRepository.findById(done.getId()).orElseThrow().getTaskTitle()).isEqualTo("Alice's work");
        assertThat(workLogRepository.findById(draft.getId())).isPresent();
    }

    @Test
    @DisplayName("an administrator can read a submitted entry but cannot edit or delete anybody's entry")
    void administratorIsReadOnlyForOthersEntries() throws Exception {
        DailyWorkLog draft = saveLog(alice, today, "Alice's draft", "2", WorkLogStatus.DRAFT);
        DailyWorkLog done = saveLog(alice, today, "Alice's work", "2", WorkLogStatus.COMPLETED);
        MockHttpSession adminSession = login(admin);

        mockMvc.perform(get("/api/work-logs/" + done.getId()).session(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.employeeName").value("Alice Employee"));
        mockMvc.perform(withCsrf(put("/api/work-logs/" + done.getId())).session(adminSession)
                        .contentType("application/json").content(json(body(today, "Edited by admin", "9", "COMPLETED"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(withCsrf(delete("/api/work-logs/" + draft.getId())).session(adminSession))
                .andExpect(status().isForbidden());
        // drafts are private to the author
        mockMvc.perform(get("/api/work-logs/" + draft.getId()).session(adminSession)).andExpect(status().isForbidden());

        assertThat(workLogRepository.findById(done.getId()).orElseThrow().getTaskTitle()).isEqualTo("Alice's work");
        assertThat(workLogRepository.findById(draft.getId())).isPresent();
    }

    @Test
    @DisplayName("the owner can edit an entry, including turning a draft into a submitted entry")
    void ownerEdits() throws Exception {
        MockHttpSession session = login(alice);
        long id = create(session, body(today, "First version", "2", "DRAFT"));

        mockMvc.perform(withCsrf(put("/api/work-logs/" + id)).session(session)
                        .contentType("application/json").content(json(body(today, "Second version", "3.25", "COMPLETED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskTitle").value("Second version"))
                .andExpect(jsonPath("$.hoursSpent").value(3.25))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        DailyWorkLog stored = workLogRepository.findDetailedById(id).orElseThrow();
        assertThat(stored.getTaskTitle()).isEqualTo("Second version");
        assertThat(stored.getEmployee().getId()).isEqualTo(alice.getId());
        mockMvc.perform(withCsrf(put("/api/work-logs/" + id)).session(session).contentType("application/json")
                        .content(json(body(today.plusDays(2), "Moved to future", "1", "COMPLETED"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withCsrf(put("/api/work-logs/999999")).session(session).contentType("application/json")
                        .content(json(body(today, "Nothing here", "1", "COMPLETED"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("editing keeps the 24-hour limit without counting the entry itself twice")
    void editRespectsDayTotal() throws Exception {
        MockHttpSession session = login(alice);
        long id = create(session, body(today, "Big task", "20", "COMPLETED"));
        create(session, body(today, "Small task", "2", "COMPLETED"));

        mockMvc.perform(withCsrf(put("/api/work-logs/" + id)).session(session).contentType("application/json")
                        .content(json(body(today, "Big task", "22", "COMPLETED"))))
                .andExpect(status().isOk());
        mockMvc.perform(withCsrf(put("/api/work-logs/" + id)).session(session).contentType("application/json")
                        .content(json(body(today, "Big task", "22.5", "COMPLETED"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("only drafts can be deleted: a draft goes (204), a submitted entry stays (409), unknown is 404")
    void deletion() throws Exception {
        MockHttpSession session = login(alice);
        long draft = create(session, body(today, "Scrap this", "1", "DRAFT"));
        long done = create(session, body(today, "Keep this", "1", "COMPLETED"));

        mockMvc.perform(withCsrf(delete("/api/work-logs/" + draft)).session(session)).andExpect(status().isNoContent());
        mockMvc.perform(withCsrf(delete("/api/work-logs/" + done)).session(session)).andExpect(status().isConflict());
        mockMvc.perform(withCsrf(delete("/api/work-logs/999999")).session(session)).andExpect(status().isNotFound());

        assertThat(workLogRepository.findById(draft)).isEmpty();
        assertThat(workLogRepository.findById(done)).isPresent();
    }

    /* ------------------------------ administrators ------------------------------ */

    @Test
    @DisplayName("employees cannot use the team list, team summary or missing-report endpoints (403)")
    void employeesCannotUseAdminViews() throws Exception {
        MockHttpSession session = login(alice);

        for (String url : List.of("/api/work-logs", "/api/work-logs/summary", "/api/work-logs/missing")) {
            mockMvc.perform(get(url).session(session)).andExpect(status().isForbidden());
            mockMvc.perform(get(url).session(session).param("employeeId", String.valueOf(bob.getId())))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("administrators see everyone's submitted entries (never drafts), and can filter, search and page")
    void adminTeamList() throws Exception {
        saveLog(alice, today, "Alice release notes", "2", WorkLogStatus.COMPLETED);
        saveLog(alice, today.minusDays(1), "Alice private draft", "1", WorkLogStatus.DRAFT);
        saveLog(bob, today, "Bob pricing model", "5", WorkLogStatus.BLOCKED);
        saveLog(bob, today.minusDays(5), "Bob old task", "3", WorkLogStatus.COMPLETED);
        MockHttpSession session = login(admin);

        mockMvc.perform(get("/api/work-logs").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[?(@.status=='DRAFT')]", hasSize(0)));
        mockMvc.perform(get("/api/work-logs").session(session).param("employeeId", String.valueOf(bob.getId())))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/work-logs").session(session).param("status", "BLOCKED"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].taskTitle").value("Bob pricing model"));
        mockMvc.perform(get("/api/work-logs").session(session).param("status", "DRAFT"))
                .andExpect(jsonPath("$.content", hasSize(0)));
        mockMvc.perform(get("/api/work-logs").session(session).param("q", "alice"))   // employee name
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/work-logs").session(session).param("q", "PRICING")) // task title, any case
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/work-logs").session(session)
                        .param("from", today.minusDays(2).toString()).param("to", today.toString()))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/work-logs").session(session).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("the team summary adds up submitted hours per employee and counts statuses, ignoring drafts")
    void teamSummary() throws Exception {
        saveLog(alice, today, "Alice A", "2", WorkLogStatus.COMPLETED);
        saveLog(alice, today, "Alice B", "1.5", WorkLogStatus.IN_PROGRESS);
        saveLog(alice, today, "Alice draft", "8", WorkLogStatus.DRAFT);
        saveLog(bob, today.minusDays(1), "Bob A", "5", WorkLogStatus.BLOCKED);
        MockHttpSession session = login(admin);

        mockMvc.perform(get("/api/work-logs/summary").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHours").value(8.5))
                .andExpect(jsonPath("$.entryCount").value(3))
                .andExpect(jsonPath("$.employeesReporting").value(2))
                .andExpect(jsonPath("$.activeEmployees").value(2))
                .andExpect(jsonPath("$.statusCounts.BLOCKED").value(1))
                .andExpect(jsonPath("$.statusCounts.DRAFT").doesNotExist())
                .andExpect(jsonPath("$.employees[0].employeeName").value("Bob Employee"))
                .andExpect(jsonPath("$.employees[0].hours").value(5))
                .andExpect(jsonPath("$.employees[1].hours").value(3.5));
        mockMvc.perform(get("/api/work-logs/summary").session(session).param("employeeId", String.valueOf(alice.getId())))
                .andExpect(jsonPath("$.totalHours").value(3.5))
                .andExpect(jsonPath("$.employeesReporting").value(1));
    }

    @Test
    @DisplayName("missing reports: weekdays without a submitted entry, minus approved leave; drafts and admins do not count")
    void missingReports() throws Exception {
        List<LocalDate> days = recentWeekdays(3);          // newest first: d1 > d2 > d3
        LocalDate d1 = days.get(0);
        LocalDate d2 = days.get(1);
        LocalDate d3 = days.get(2);
        // Alice reported only on d1 and has a draft (not counted) on d2. Bob reported on all three days.
        saveLog(alice, d1, "Alice reported", "4", WorkLogStatus.COMPLETED);
        saveLog(alice, d2, "Alice only a draft", "4", WorkLogStatus.DRAFT);
        for (LocalDate day : days) {
            saveLog(bob, day, "Bob reported " + day, "4", WorkLogStatus.COMPLETED);
        }
        MockHttpSession session = login(admin);

        mockMvc.perform(get("/api/work-logs/missing").session(session)
                        .param("from", d3.toString()).param("to", d1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].employeeName").value("Alice Employee"))
                .andExpect(jsonPath("$[0].missingCount").value(2))
                .andExpect(jsonPath("$[0].missingDates[0]").value(d3.toString()))
                .andExpect(jsonPath("$[0].missingDates[1]").value(d2.toString()));

        // Approved leave on d2 removes that day; pending leave does not.
        LeaveRequest approved = leaveRepository.save(new LeaveRequest(alice, LeaveType.CASUAL, d2, d2, 1, "Day off"));
        approved.approve(admin, java.time.Instant.now());
        leaveRepository.save(approved);
        leaveRepository.save(new LeaveRequest(alice, LeaveType.CASUAL, d3, d3, 1, "Maybe off"));

        mockMvc.perform(get("/api/work-logs/missing").session(session)
                        .param("from", d3.toString()).param("to", d1.toString()))
                .andExpect(jsonPath("$[0].missingCount").value(1))
                .andExpect(jsonPath("$[0].missingDates[0]").value(d3.toString()));
    }

    @Test
    @DisplayName("missing reports ignore today and the future, deactivated employees and days before joining")
    void missingReportsEdgeCases() throws Exception {
        LocalDate yesterday = recentWeekdays(1).get(0);
        User carol = createUser("Carol Newcomer", "carol@example.com", com.worktrack.entity.Role.EMPLOYEE, "Sales");
        carol.setJoiningDate(yesterday);                      // joined on the last weekday: nothing earlier counts
        userRepository.save(carol);
        User dave = createUser("Dave Gone", "dave@example.com", com.worktrack.entity.Role.EMPLOYEE, "Sales");
        dave.setStatus(EmploymentStatus.INACTIVE);
        userRepository.save(dave);
        MockHttpSession session = login(admin);

        MvcResult result = mockMvc.perform(get("/api/work-logs/missing").session(session)
                        .param("from", yesterday.minusDays(10).toString()).param("to", today.plusDays(5).toString()))
                .andExpect(status().isOk()).andReturn();

        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode row : rows) {
            assertThat(row.get("employeeName").asText()).isNotIn("Dave Gone", "Ada Admin");
            for (JsonNode date : row.get("missingDates")) {
                assertThat(LocalDate.parse(date.asText())).isBefore(today);   // today is still open
            }
            if (row.get("employeeName").asText().equals("Carol Newcomer")) {
                assertThat(row.get("missingDates")).hasSize(1);               // only her joining day
                assertThat(row.get("missingDates").get(0).asText()).isEqualTo(yesterday.toString());
            }
        }
        mockMvc.perform(get("/api/work-logs/missing").session(session)
                        .param("from", today.minusDays(100).toString()).param("to", today.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/work-logs/missing").session(session)
                        .param("from", today.toString()).param("to", today.minusDays(1).toString()))
                .andExpect(status().isBadRequest());
        // a range that starts today has nothing to check yet
        mockMvc.perform(get("/api/work-logs/missing").session(session)
                        .param("from", today.toString()).param("to", today.toString()))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("a deactivated employee's session ends, so they can no longer read or write work reports")
    void deactivatedEmployeeIsLockedOut() throws Exception {
        MockHttpSession session = login(alice);
        alice.setStatus(EmploymentStatus.INACTIVE);
        userRepository.save(alice);

        mockMvc.perform(get("/api/work-logs/my").session(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(jsonPost("/api/work-logs", session, body(today, "After exit", "1", "COMPLETED")))
                .andExpect(status().isUnauthorized());
    }

    /* ------------------------------ concurrency helper ------------------------------ */

    private static List<Integer> runTogether(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
