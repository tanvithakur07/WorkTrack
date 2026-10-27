package com.worktrack.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.worktrack.entity.AuditEvent;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class AuditIntegrationTest extends IntegrationTestBase {

    private List<AuditEvent> events() {
        return auditEventRepository.findAll();
    }

    private boolean has(String action, AuditOutcome outcome) {
        return events().stream().anyMatch(e -> e.getAction().equals(action) && e.getOutcome() == outcome);
    }

    private void tryLogin(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/login").with(csrfToken()).contentType("application/json")
                .content(json(Map.of("email", email, "password", password))));
    }

    @Test
    @DisplayName("login success, login failure and logout each leave an audit row; the wrong password itself is never stored")
    void loginEvents() throws Exception {
        tryLogin("alice@example.com", "WrongPassword9");
        MockHttpSession session = login(alice);
        mockMvc.perform(post("/api/auth/logout").session(session).with(csrfToken()));

        assertThat(has("LOGIN_FAILURE", AuditOutcome.FAILURE)).isTrue();
        assertThat(has("LOGIN_SUCCESS", AuditOutcome.SUCCESS)).isTrue();
        assertThat(has("LOGOUT", AuditOutcome.SUCCESS)).isTrue();
        assertThat(events()).noneMatch(e -> String.valueOf(e.getSummary()).contains("WrongPassword9"));
        assertThat(events()).noneMatch(e -> String.valueOf(e.getActorLabel()).contains("WrongPassword9"));
    }

    @Test
    @DisplayName("a mistyped password in the e-mail box is not stored as the attempted login name")
    void passwordTypedAsEmailIsNotStored() throws Exception {
        tryLogin("MyP4ssword With Spaces", "whatever1A");

        assertThat(events()).anyMatch(e -> "LOGIN_FAILURE".equals(e.getAction()));
        assertThat(events()).noneMatch(e -> String.valueOf(e.getActorLabel()).contains("MyP4ssword"));
    }

    @Test
    @DisplayName("leave submit/approve/reject/cancel and work-log create/update/delete are audited with the real actor")
    void businessEvents() throws Exception {
        MockHttpSession aliceSession = login(alice);
        LocalDate monday = futureMonday();
        Map<String, Object> leave = new LinkedHashMap<>();
        leave.put("leaveType", "CASUAL");
        leave.put("startDate", monday.toString());
        leave.put("endDate", monday.plusDays(1).toString());
        leave.put("reason", "Family trip");
        mockMvc.perform(jsonPost("/api/leaves", aliceSession, leave)).andExpect(status().isCreated());

        LeaveRequest toApprove = saveLeave(bob, LeaveType.CASUAL, monday.plusDays(7), monday.plusDays(8));
        mockMvc.perform(patch("/api/leaves/" + toApprove.getId() + "/approve").session(login(admin)).with(csrfToken()))
                .andExpect(status().isOk());

        Map<String, Object> log = new LinkedHashMap<>();
        log.put("workDate", LocalDate.now().minusDays(1).toString());
        log.put("taskTitle", "Follow up leads");
        log.put("description", "Called prospects");
        log.put("hoursSpent", new BigDecimal("2.5"));
        log.put("status", "COMPLETED");
        String created = mockMvc.perform(jsonPost("/api/work-logs", aliceSession, log)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long logId = objectMapper.readTree(created).get("id").asLong();
        mockMvc.perform(put("/api/work-logs/" + logId).session(aliceSession).with(csrfToken())
                .contentType("application/json").content(json(log))).andExpect(status().isOk());
        // only drafts can be deleted, so the delete is audited on a separate draft entry
        log.put("status", "DRAFT");
        String draft = mockMvc.perform(jsonPost("/api/work-logs", aliceSession, log)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long draftId = objectMapper.readTree(draft).get("id").asLong();
        mockMvc.perform(delete("/api/work-logs/" + draftId).session(aliceSession).with(csrfToken())).andExpect(status().isNoContent());

        assertThat(has("LEAVE_SUBMITTED", AuditOutcome.SUCCESS)).isTrue();
        assertThat(has("LEAVE_APPROVED", AuditOutcome.SUCCESS)).isTrue();
        assertThat(has("WORKLOG_CREATED", AuditOutcome.SUCCESS)).isTrue();
        assertThat(has("WORKLOG_UPDATED", AuditOutcome.SUCCESS)).isTrue();
        assertThat(has("WORKLOG_DELETED", AuditOutcome.SUCCESS)).isTrue();
        AuditEvent approved = events().stream().filter(e -> e.getAction().equals("LEAVE_APPROVED")).findFirst().orElseThrow();
        assertThat(approved.getActorUserId()).isEqualTo(admin.getId());
        assertThat(approved.getActorRole()).isEqualTo("ADMIN");
        assertThat(approved.getEntityId()).isEqualTo(toApprove.getId());
    }

    @Test
    @DisplayName("no audit row of any kind contains a password, hash or session id")
    void noSecretsAnywhere() throws Exception {
        login(alice);
        tryLogin("alice@example.com", "Another-Wrong1");
        mockMvc.perform(jsonPost("/api/employees", login(admin), Map.of(
                "fullName", "New Person", "email", "new@example.com", "password", "Welcome-123",
                "department", "Ops", "jobTitle", "Analyst", "joiningDate", "2025-01-10", "role", "EMPLOYEE")));

        for (AuditEvent e : events()) {
            String all = String.join("|", String.valueOf(e.getSummary()), String.valueOf(e.getActorLabel()));
            assertThat(all).doesNotContain("Welcome-123").doesNotContain("Another-Wrong1").doesNotContain("$2a$").doesNotContain("JSESSIONID");
        }
    }

    @Test
    @DisplayName("only administrators can read the audit log; it can be filtered by action and outcome")
    void readAccessAndFilters() throws Exception {
        tryLogin("alice@example.com", "WrongPassword9");
        login(alice);

        mockMvc.perform(get("/api/audit")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/audit").session(login(alice))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit").param("action", "LOGIN_FAILURE").param("outcome", "FAILURE").session(login(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("LOGIN_FAILURE"))
                .andExpect(header().exists("X-Request-Id"));
        mockMvc.perform(get("/api/audit").param("action", "NOT_AN_ACTION").session(login(admin))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/audit").param("from", "2026-03-05").param("to", "2026-03-01").session(login(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("there is no way to change or delete audit rows through the API")
    void noWriteEndpoints() throws Exception {
        MockHttpSession adminSession = login(admin);
        mockMvc.perform(post("/api/audit").session(adminSession).with(csrfToken()).contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/api/audit/1").session(adminSession).with(csrfToken()).contentType("application/json").content("{}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(delete("/api/audit/1").session(adminSession).with(csrfToken())).andExpect(status().is4xxClientError());
        mockMvc.perform(delete("/api/audit").session(adminSession).with(csrfToken())).andExpect(status().isMethodNotAllowed());
    }
}
