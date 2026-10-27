package com.worktrack.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveType;
import com.worktrack.entity.Notification;
import com.worktrack.entity.NotificationType;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/**
 * Who is told about what. Only the outbox rows are inspected (nothing is sent), so these tests need no mail mock.
 * Recipients: active administrators with a valid address, or the employee concerned; never unrelated people.
 */
class NotificationEventsIntegrationTest extends IntegrationTestBase {

    private List<Notification> rows() {
        return notificationRepository.findAll();
    }

    private List<String> recipients(NotificationType type) {
        return rows().stream().filter(n -> n.getType() == type).map(Notification::getRecipientEmail).sorted().toList();
    }

    private Map<String, Object> leaveBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("leaveType", "CASUAL");
        body.put("startDate", futureMonday().toString());
        body.put("endDate", futureMonday().plusDays(1).toString());
        body.put("reason", "Family function");
        return body;
    }

    private Map<String, Object> updateBody(User user, String department, String status, String role) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", user.getFullName());
        body.put("email", user.getEmail());
        body.put("department", department);
        body.put("jobTitle", user.getJobTitle());
        body.put("joiningDate", user.getJoiningDate().toString());
        body.put("role", role);
        body.put("status", status);
        return body;
    }

    @Test
    @DisplayName("a leave request reaches every ACTIVE administrator with a valid address, and nobody else")
    void submissionRecipients() throws Exception {
        createUser("Second Admin", "second.admin@example.com", Role.ADMIN, "Administration");
        User inactiveAdmin = createUser("Retired Admin", "retired@example.com", Role.ADMIN, "Administration");
        inactiveAdmin.setStatus(EmploymentStatus.INACTIVE);
        userRepository.save(inactiveAdmin);
        createUser("No Mail Admin", "not-an-address", Role.ADMIN, "Administration");

        mockMvc.perform(jsonPost("/api/leaves", login(alice), leaveBody())).andExpect(status().isCreated());

        assertThat(recipients(NotificationType.LEAVE_SUBMITTED)).containsExactly("ada@example.com", "second.admin@example.com");
        assertThat(rows()).hasSize(2); // not the submitter, not bob, not the inactive or invalid administrators
    }

    @Test
    @DisplayName("approval and rejection each produce one message, to the employee who asked, and to nobody else")
    void decisionRecipients() throws Exception {
        LeaveRequest first = saveLeave(alice, LeaveType.CASUAL, futureMonday(), futureMonday().plusDays(1));
        LeaveRequest second = saveLeave(bob, LeaveType.SICK, futureMonday().plusDays(7), futureMonday().plusDays(8));
        MockHttpSession adminSession = login(admin);

        mockMvc.perform(patch("/api/leaves/" + first.getId() + "/approve").session(adminSession).with(csrfToken())).andExpect(status().isOk());
        mockMvc.perform(jsonPatch("/api/leaves/" + second.getId() + "/reject", adminSession, Map.of("reason", "Busy period")))
                .andExpect(status().isOk());

        assertThat(recipients(NotificationType.LEAVE_APPROVED)).containsExactly("alice@example.com");
        assertThat(recipients(NotificationType.LEAVE_REJECTED)).containsExactly("bob@example.com");
        assertThat(rows()).hasSize(2);
        assertThat(rows().stream().filter(n -> n.getType() == NotificationType.LEAVE_REJECTED).findFirst().orElseThrow().getBodyText())
                .contains("Busy period");
    }

    @Test
    @DisplayName("cancelling a pending request tells the administrators, not the employee who cancelled it")
    void cancellationRecipients() throws Exception {
        LeaveRequest pending = saveLeave(alice, LeaveType.CASUAL, futureMonday(), futureMonday().plusDays(1));

        mockMvc.perform(patch("/api/leaves/" + pending.getId() + "/cancel").session(login(alice)).with(csrfToken())).andExpect(status().isOk());

        assertThat(recipients(NotificationType.LEAVE_CANCELLED)).containsExactly("ada@example.com");
    }

    @Test
    @DisplayName("deactivating tells the employee and the other administrators; reactivating does the same with its own type")
    void deactivationAndReactivation() throws Exception {
        createUser("Second Admin", "second.admin@example.com", Role.ADMIN, "Administration");
        MockHttpSession adminSession = login(admin);

        mockMvc.perform(delete("/api/employees/" + alice.getId()).session(adminSession).with(csrfToken())).andExpect(status().isNoContent());
        assertThat(recipients(NotificationType.EMPLOYEE_DEACTIVATED)).containsExactly("alice@example.com", "second.admin@example.com");

        User deactivated = userRepository.findById(alice.getId()).orElseThrow();
        mockMvc.perform(jsonPut("/api/employees/" + alice.getId(), adminSession, updateBody(deactivated, "Engineering", "ACTIVE", "EMPLOYEE")))
                .andExpect(status().isOk());
        assertThat(recipients(NotificationType.EMPLOYEE_REACTIVATED)).containsExactly("alice@example.com", "second.admin@example.com");
        assertThat(recipients(NotificationType.EMPLOYEE_DEACTIVATED)).as("no repeat of the earlier event").hasSize(2);
    }

    @Test
    @DisplayName("deactivating an already inactive account again sends nothing more")
    void repeatedDeactivationIsQuiet() throws Exception {
        MockHttpSession adminSession = login(admin);
        mockMvc.perform(delete("/api/employees/" + bob.getId()).session(adminSession).with(csrfToken())).andExpect(status().isNoContent());
        long after = notificationRepository.count();
        mockMvc.perform(delete("/api/employees/" + bob.getId()).session(adminSession).with(csrfToken())).andExpect(status().isNoContent());

        assertThat(notificationRepository.count()).isEqualTo(after);
    }

    @Test
    @DisplayName("a profile change tells the employee; an update that changes nothing sends nothing")
    void profileUpdate() throws Exception {
        MockHttpSession adminSession = login(admin);

        mockMvc.perform(jsonPut("/api/employees/" + alice.getId(), adminSession, updateBody(alice, alice.getDepartment(), "ACTIVE", "EMPLOYEE")))
                .andExpect(status().isOk());
        assertThat(notificationRepository.count()).isZero();

        mockMvc.perform(jsonPut("/api/employees/" + alice.getId(), adminSession, updateBody(alice, "Marketing", "ACTIVE", "EMPLOYEE")))
                .andExpect(status().isOk());
        assertThat(recipients(NotificationType.EMPLOYEE_UPDATED)).containsExactly("alice@example.com");
        assertThat(rows().get(0).getBodyText()).contains("department");
    }

    @Test
    @DisplayName("a role change and a password change are security notices to the affected person only, without any password")
    void securityNotices() throws Exception {
        MockHttpSession adminSession = login(admin);
        Map<String, Object> promote = updateBody(alice, alice.getDepartment(), "ACTIVE", "ADMIN");
        promote.put("password", "Reset-pass-99");
        mockMvc.perform(jsonPut("/api/employees/" + alice.getId(), adminSession, promote)).andExpect(status().isOk());

        assertThat(recipients(NotificationType.ACCOUNT_SECURITY)).containsExactly("alice@example.com", "alice@example.com");
        assertThat(rows()).allSatisfy(n -> {
            assertThat(n.getBodyText()).doesNotContain("Reset-pass-99");
            assertThat(n.getBodyHtml()).doesNotContain("Reset-pass-99");
        });

        MockHttpSession bobSession = login(bob);
        mockMvc.perform(post("/api/auth/change-password").session(bobSession).with(csrfToken()).contentType("application/json")
                .content(json(Map.of("currentPassword", PASSWORD, "newPassword", "Brand-new-pass2")))).andExpect(status().isNoContent());
        assertThat(rows().stream().filter(n -> "bob@example.com".equals(n.getRecipientEmail())).toList())
                .singleElement().satisfies(n -> assertThat(n.getBodyText()).doesNotContain("Brand-new-pass2"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder jsonPut(String url, MockHttpSession session, Object body) throws Exception {
        return put(url).session(session).with(csrfToken()).contentType("application/json").content(json(body));
    }
}
