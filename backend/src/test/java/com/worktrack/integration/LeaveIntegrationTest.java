package com.worktrack.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveStatus;
import com.worktrack.entity.LeaveType;

class LeaveIntegrationTest extends IntegrationTestBase {

    private Map<String, Object> leaveBody(LeaveType type, LocalDate start, LocalDate end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("leaveType", type.name());
        body.put("startDate", start.toString());
        body.put("endDate", end.toString());
        body.put("reason", "Family function");
        return body;
    }

    private long submit(MockHttpSession session, LocalDate start, LocalDate end) throws Exception {
        MvcResult result = mockMvc.perform(jsonPost("/api/leaves", session, leaveBody(LeaveType.CASUAL, start, end)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }

    /* ------------------------------ submitting ------------------------------ */

    @Test
    @DisplayName("an employee can submit a request: 201, PENDING, weekends not counted")
    void submitsRequest() throws Exception {
        MockHttpSession session = login(alice);
        LocalDate monday = futureMonday();

        // Monday to the Monday after = 6 working days
        mockMvc.perform(jsonPost("/api/leaves", session, leaveBody(LeaveType.EARNED, monday, monday.plusDays(7))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.days").value(6))
                .andExpect(jsonPath("$.employeeId").value(alice.getId()))
                .andExpect(jsonPath("$.employeeName").value("Alice Employee"));
    }

    @Test
    @DisplayName("an end date before the start date is a 400")
    void rejectsInvalidDateRange() throws Exception {
        MockHttpSession session = login(alice);
        LocalDate monday = futureMonday();

        mockMvc.perform(jsonPost("/api/leaves", session, leaveBody(LeaveType.CASUAL, monday.plusDays(3), monday)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("End date cannot be before start date"));
    }

    @Test
    @DisplayName("casual leave cannot start in the past; a sick day from yesterday is allowed")
    void pastDatePolicy() throws Exception {
        MockHttpSession session = login(alice);
        LocalDate yesterday = LocalDate.now().minusDays(1);

        mockMvc.perform(jsonPost("/api/leaves", session, leaveBody(LeaveType.CASUAL, yesterday, yesterday.plusDays(1))))
                .andExpect(status().isBadRequest());

        // Pick a weekday inside the 7-day sick window so the request has at least one leave day.
        LocalDate weekday = LocalDate.now().minusDays(1);
        while (weekday.getDayOfWeek().getValue() > 5) {
            weekday = weekday.minusDays(1);
        }
        mockMvc.perform(jsonPost("/api/leaves", session, leaveBody(LeaveType.SICK, weekday, weekday)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("an empty or incomplete body is a 400 with field errors")
    void rejectsEmptyRequest() throws Exception {
        MockHttpSession session = login(alice);

        mockMvc.perform(jsonPost("/api/leaves", session, Map.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.leaveType").exists())
                .andExpect(jsonPath("$.fieldErrors.startDate").exists())
                .andExpect(jsonPath("$.fieldErrors.endDate").exists())
                .andExpect(jsonPath("$.fieldErrors.reason").exists());
    }

    @Test
    @DisplayName("an unknown leave type is a 400")
    void rejectsUnknownLeaveType() throws Exception {
        MockHttpSession session = login(alice);
        Map<String, Object> body = leaveBody(LeaveType.CASUAL, futureMonday(), futureMonday());
        body.put("leaveType", "HOLIDAY");

        mockMvc.perform(jsonPost("/api/leaves", session, body)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a request that overlaps the employee's own pending request is a 409")
    void rejectsOverlappingSubmission() throws Exception {
        MockHttpSession session = login(alice);
        LocalDate monday = futureMonday();
        submit(session, monday, monday.plusDays(2));

        mockMvc.perform(jsonPost("/api/leaves", session, leaveBody(LeaveType.CASUAL, monday.plusDays(2), monday.plusDays(4))))
                .andExpect(status().isConflict());

        // Different employee, same dates: allowed
        MockHttpSession bobSession = login(bob);
        mockMvc.perform(jsonPost("/api/leaves", bobSession, leaveBody(LeaveType.CASUAL, monday, monday.plusDays(2))))
                .andExpect(status().isCreated());
    }

    /* ------------------------------ viewing ------------------------------ */

    @Test
    @DisplayName("/api/leaves/my shows only the caller's own requests")
    void myLeavesAreScopedToCaller() throws Exception {
        LocalDate monday = futureMonday();
        saveLeave(alice, LeaveType.CASUAL, monday, monday.plusDays(2));
        saveLeave(bob, LeaveType.SICK, monday.plusDays(7), monday.plusDays(9));

        mockMvc.perform(get("/api/leaves/my").session(login(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].employeeId").value(alice.getId()));

        // An employeeId parameter does not widen the scope
        mockMvc.perform(get("/api/leaves/my").session(login(alice)).param("employeeId", String.valueOf(bob.getId())))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].employeeId").value(alice.getId()));
    }

    @Test
    @DisplayName("an employee cannot open another employee's request: 403")
    void cannotViewOthersLeave() throws Exception {
        LeaveRequest bobsLeave = saveLeave(bob, LeaveType.CASUAL, futureMonday(), futureMonday().plusDays(1));

        mockMvc.perform(get("/api/leaves/" + bobsLeave.getId()).session(login(alice)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/leaves/" + bobsLeave.getId()).session(login(bob)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/leaves/" + bobsLeave.getId()).session(login(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/leaves/987654").session(login(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the admin list needs the ADMIN role and can be filtered by status, employee and date")
    void adminListAndFilters() throws Exception {
        LocalDate monday = futureMonday();
        LeaveRequest first = saveLeave(alice, LeaveType.CASUAL, monday, monday.plusDays(2));
        LeaveRequest second = saveLeave(bob, LeaveType.EARNED, monday.plusDays(14), monday.plusDays(16));
        second.approve(admin, java.time.Instant.now());
        leaveRepository.save(second);

        mockMvc.perform(get("/api/leaves").session(login(alice))).andExpect(status().isForbidden());

        MockHttpSession adminSession = login(admin);
        mockMvc.perform(get("/api/leaves").session(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/leaves").session(adminSession).param("status", "APPROVED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(second.getId()));
        mockMvc.perform(get("/api/leaves").session(adminSession).param("employeeId", String.valueOf(alice.getId())))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(first.getId()));
        mockMvc.perform(get("/api/leaves").session(adminSession)
                        .param("from", monday.plusDays(10).toString()).param("to", monday.plusDays(20).toString()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(second.getId()));
        mockMvc.perform(get("/api/leaves").session(adminSession)
                        .param("from", monday.plusDays(5).toString()).param("to", monday.toString()))
                .andExpect(status().isBadRequest());
    }

    /* ------------------------------ approving and rejecting ------------------------------ */

    @Test
    @DisplayName("an administrator approves a pending request; who and when are stored; approving twice is a 409")
    void adminApproves() throws Exception {
        MockHttpSession aliceSession = login(alice);
        long id = submit(aliceSession, futureMonday(), futureMonday().plusDays(2));
        MockHttpSession adminSession = login(admin);

        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/approve", adminSession, Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedByName").value("Ada Admin"))
                .andExpect(jsonPath("$.reviewedAt").exists());

        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/approve", adminSession, Map.of()))
                .andExpect(status().isConflict());
        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/reject", adminSession, Map.of("reason", "Too late")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("rejecting needs a reason, then stores it")
    void adminRejects() throws Exception {
        long id = submit(login(alice), futureMonday(), futureMonday().plusDays(1));
        MockHttpSession adminSession = login(admin);

        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/reject", adminSession, Map.of("reason", " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reason").exists());

        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/reject", adminSession, Map.of("reason", "Release week")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Release week"))
                .andExpect(jsonPath("$.reviewedByName").value("Ada Admin"));
    }

    @Test
    @DisplayName("employees cannot approve or reject, not even their own request: 403")
    void employeesCannotReview() throws Exception {
        MockHttpSession aliceSession = login(alice);
        long id = submit(aliceSession, futureMonday(), futureMonday().plusDays(1));

        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/approve", aliceSession, Map.of()))
                .andExpect(status().isForbidden());
        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/reject", aliceSession, Map.of("reason", "Nope")))
                .andExpect(status().isForbidden());
        assertThat(leaveRepository.findById(id).orElseThrow().getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test
    @DisplayName("an administrator cannot approve or reject their own request: 403")
    void adminCannotReviewOwnRequest() throws Exception {
        MockHttpSession adminSession = login(admin);
        long id = submit(adminSession, futureMonday(), futureMonday().plusDays(1));

        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/approve", adminSession, Map.of()))
                .andExpect(status().isForbidden());
        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/reject", adminSession, Map.of("reason", "Nope")))
                .andExpect(status().isForbidden());
        assertThat(leaveRepository.findById(id).orElseThrow().getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test
    @DisplayName("approval is refused when it would overlap an already approved request for the same employee")
    void approvalRefusedOnOverlap() throws Exception {
        LocalDate monday = futureMonday();
        LeaveRequest approved = saveLeave(alice, LeaveType.EARNED, monday, monday.plusDays(4));
        approved.approve(admin, java.time.Instant.now());
        leaveRepository.save(approved);
        // Written directly to the database to simulate a pending request created before the approval above
        LeaveRequest overlapping = saveLeave(alice, LeaveType.CASUAL, monday.plusDays(2), monday.plusDays(3));

        mockMvc.perform(jsonPatch("/api/leaves/" + overlapping.getId() + "/approve", login(admin), Map.of()))
                .andExpect(status().isConflict());
        assertThat(leaveRepository.findById(overlapping.getId()).orElseThrow().getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test
    @DisplayName("a request for different dates is approved normally even if the employee has other approved leave")
    void nonOverlappingApprovalWorks() throws Exception {
        LocalDate monday = futureMonday();
        LeaveRequest approved = saveLeave(alice, LeaveType.EARNED, monday, monday.plusDays(4));
        approved.approve(admin, java.time.Instant.now());
        leaveRepository.save(approved);
        LeaveRequest later = saveLeave(alice, LeaveType.CASUAL, monday.plusDays(14), monday.plusDays(15));

        mockMvc.perform(jsonPatch("/api/leaves/" + later.getId() + "/approve", login(admin), Map.of()))
                .andExpect(status().isOk());
    }

    /* ------------------------------ cancelling ------------------------------ */

    @Test
    @DisplayName("an employee can cancel their own pending request")
    void ownerCancels() throws Exception {
        MockHttpSession aliceSession = login(alice);
        long id = submit(aliceSession, futureMonday(), futureMonday().plusDays(1));

        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/cancel", aliceSession, Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("nobody can cancel another employee's request; approved requests cannot be cancelled")
    void cancelRules() throws Exception {
        MockHttpSession aliceSession = login(alice);
        long id = submit(aliceSession, futureMonday(), futureMonday().plusDays(1));

        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/cancel", login(bob), Map.of()))
                .andExpect(status().isForbidden());
        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/cancel", login(admin), Map.of()))
                .andExpect(status().isForbidden());

        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/approve", login(admin), Map.of())).andExpect(status().isOk());
        mockMvc.perform(jsonPatch("/api/leaves/" + id + "/cancel", aliceSession, Map.of()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("without a CSRF token even a valid session cannot change a request")
    void changesNeedCsrfToken() throws Exception {
        MockHttpSession aliceSession = login(alice);
        long id = submit(aliceSession, futureMonday(), futureMonday().plusDays(1));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/leaves/" + id + "/cancel").session(aliceSession))
                .andExpect(status().isForbidden());
        assertThat(leaveRepository.findById(id).orElseThrow().getStatus()).isEqualTo(LeaveStatus.PENDING);
    }
}
