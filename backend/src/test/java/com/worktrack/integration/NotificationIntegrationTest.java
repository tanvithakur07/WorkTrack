package com.worktrack.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveType;
import com.worktrack.entity.Notification;
import com.worktrack.entity.NotificationStatus;
import com.worktrack.entity.NotificationType;
import com.worktrack.entity.User;
import com.worktrack.mail.EmailDeliveryException;
import com.worktrack.mail.EmailSender;
import com.worktrack.service.NotificationDispatcher;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Leave e-mails end to end against H2. The mail system is a mock: no real SMTP server is involved. */
class NotificationIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private EmailSender emailSender;
    @Autowired
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void mockSender() {
        reset(emailSender);
        when(emailSender.channel()).thenReturn("SMTP");
    }

    private Map<String, Object> leaveBody() {
        LocalDate monday = futureMonday();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("leaveType", LeaveType.CASUAL.name());
        body.put("startDate", monday.toString());
        body.put("endDate", monday.plusDays(1).toString());
        body.put("reason", "Family function");
        return body;
    }

    private long submitAsAlice() throws Exception {
        MockHttpSession session = login(alice);
        String response = mockMvc.perform(jsonPost("/api/leaves", session, leaveBody()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private List<Notification> all() {
        return notificationRepository.findAll();
    }

    @Test
    @DisplayName("submitting a request queues one LEAVE_SUBMITTED message for the administrator (not the employee), PENDING, not yet sent")
    void submitQueuesMessage() throws Exception {
        long leaveId = submitAsAlice();

        assertThat(all()).hasSize(1);
        Notification n = all().get(0);
        assertThat(n.getType()).isEqualTo(NotificationType.LEAVE_SUBMITTED);
        assertThat(n.getRecipientEmail()).isEqualTo("ada@example.com");
        assertThat(n.getBodyText()).contains("Alice Employee");
        assertThat(n.getLeaveRequestId()).isEqualTo(leaveId);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        verify(emailSender, never()).send(any()); // queuing never sends inside the request
    }

    @Test
    @DisplayName("approving and rejecting queue the matching messages, the rejection carrying the reason")
    void decisionsQueueMessages() throws Exception {
        LeaveRequest first = saveLeave(alice, LeaveType.CASUAL, futureMonday(), futureMonday().plusDays(1));
        LeaveRequest second = saveLeave(alice, LeaveType.SICK, futureMonday().plusDays(7), futureMonday().plusDays(8));
        MockHttpSession adminSession = login(admin);

        mockMvc.perform(patchAs("/api/leaves/" + first.getId() + "/approve", adminSession)).andExpect(status().isOk());
        mockMvc.perform(jsonPatch("/api/leaves/" + second.getId() + "/reject", adminSession, Map.of("reason", "Team is short-staffed")))
                .andExpect(status().isOk());

        assertThat(all()).extracting(Notification::getType)
                .containsExactlyInAnyOrder(NotificationType.LEAVE_APPROVED, NotificationType.LEAVE_REJECTED);
        Notification rejected = all().stream().filter(n -> n.getType() == NotificationType.LEAVE_REJECTED).findFirst().orElseThrow();
        assertThat(rejected.getBodyText()).contains("Team is short-staffed");
    }

    @Test
    @DisplayName("a refused decision (already decided) queues nothing extra")
    void failedDecisionQueuesNothing() throws Exception {
        LeaveRequest leave = saveLeave(alice, LeaveType.CASUAL, futureMonday(), futureMonday().plusDays(1));
        MockHttpSession adminSession = login(admin);
        mockMvc.perform(patchAs("/api/leaves/" + leave.getId() + "/approve", adminSession)).andExpect(status().isOk());
        mockMvc.perform(patchAs("/api/leaves/" + leave.getId() + "/approve", adminSession)).andExpect(status().isConflict());

        assertThat(all()).hasSize(1);
    }

    @Test
    @DisplayName("the dispatcher sends a queued message and only then marks it SENT with the channel")
    void dispatcherSends() throws Exception {
        submitAsAlice();

        assertThat(dispatcher.processDue()).isEqualTo(1);

        Notification n = all().get(0);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getAttempts()).isEqualTo(1);
        assertThat(n.getChannel()).isEqualTo("SMTP");
        assertThat(n.getSentAt()).isNotNull();
        // nothing left to do
        assertThat(dispatcher.processDue()).isZero();
    }

    @Test
    @DisplayName("a mail outage does not undo the leave decision; the message waits for a retry with the address scrubbed from the error")
    void mailFailureDoesNotAffectDecision() throws Exception {
        LeaveRequest leave = saveLeave(alice, LeaveType.CASUAL, futureMonday(), futureMonday().plusDays(1));
        doThrow(new EmailDeliveryException("550 rejected alice@example.com", false, null)).when(emailSender).send(any());
        MockHttpSession adminSession = login(admin);

        mockMvc.perform(patchAs("/api/leaves/" + leave.getId() + "/approve", adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        Notification queued = all().get(0);
        assertThat(queued.getNextAttemptAt()).as("a freshly queued message is due immediately").isBeforeOrEqualTo(Instant.now());
        assertThat(dispatcher.processDue()).as("messages handled by the first batch").isEqualTo(1);

        assertThat(leaveRepository.findById(leave.getId()).orElseThrow().getStatus().name()).isEqualTo("APPROVED");
        Notification n = all().get(0);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getAttempts()).isEqualTo(1);
        assertThat(n.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(n.getLastError()).isNotBlank().doesNotContain("alice@example.com");
        assertThat(all()).as("a retry never creates another logical notification").hasSize(1);
        assertThat(dispatcher.processDue()).isZero(); // not due yet: no hot retry loop
    }

    @Test
    @DisplayName("an employee with an invalid address still gets a decision, just no e-mail")
    void invalidAddressSkipsEmail() throws Exception {
        User badAddress = createUser("No Mail", "not-an-address", com.worktrack.entity.Role.EMPLOYEE, "Support");
        LeaveRequest leave = saveLeave(badAddress, LeaveType.CASUAL, futureMonday(), futureMonday().plusDays(1));

        mockMvc.perform(patchAs("/api/leaves/" + leave.getId() + "/approve", login(admin))).andExpect(status().isOk());

        assertThat(all()).isEmpty();
    }

    @Test
    @DisplayName("a message stuck PROCESSING with an expired lease (crashed worker) is picked up again")
    void expiredLeaseIsRecovered() throws Exception {
        submitAsAlice();
        Long id = all().get(0).getId();
        assertThat(notificationRepository.claim(id, Instant.now(), Instant.now().minusSeconds(60))).isEqualTo(1);
        assertThat(notificationRepository.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.PROCESSING);

        assertThat(dispatcher.processDue()).isEqualTo(1);

        Notification n = notificationRepository.findById(id).orElseThrow();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("a second claim on a row that is already claimed (lease still valid) gets nothing")
    void secondClaimLoses() throws Exception {
        submitAsAlice();
        Long id = all().get(0).getId();
        Instant now = Instant.now();

        assertThat(notificationRepository.claim(id, now, now.plusSeconds(120))).isEqualTo(1);
        assertThat(notificationRepository.claim(id, now, now.plusSeconds(120))).isZero();
    }

    @Test
    @DisplayName("admin retry: FAILED goes back to PENDING (audited); anything else is 409; employees get 403; CSRF is required")
    void adminRetry() throws Exception {
        submitAsAlice();
        Long id = all().get(0).getId();
        MockHttpSession adminSession = login(admin);

        // not failed yet -> 409
        mockMvc.perform(post("/api/notifications/" + id + "/retry").session(adminSession).with(csrfToken()))
                .andExpect(status().isConflict());

        doThrow(new EmailDeliveryException("535 auth failed", true, null)).when(emailSender).send(any());
        dispatcher.processDue();
        assertThat(notificationRepository.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.FAILED);

        mockMvc.perform(post("/api/notifications/" + id + "/retry").session(login(alice)).with(csrfToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/notifications/" + id + "/retry").session(adminSession)) // no CSRF token
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/notifications/" + id + "/retry").session(adminSession).with(csrfToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));

        assertThat(auditEventRepository.findAll()).extracting(e -> e.getAction() + ":" + e.getOutcome())
                .contains("NOTIFICATION_RETRY:SUCCESS", "NOTIFICATION_RETRY:FAILURE");
    }

    @Test
    @DisplayName("the queue API lists messages without exposing subject or body, and only to administrators")
    void queueApi() throws Exception {
        submitAsAlice();

        mockMvc.perform(get("/api/notifications").session(login(alice))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        String body = mockMvc.perform(get("/api/notifications").session(login(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Family function").doesNotContain("bodyHtml").doesNotContain("subject");
        mockMvc.perform(get("/api/notifications/summary").session(login(admin))).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchAs(String url, MockHttpSession session) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url).session(session).with(csrfToken());
    }
}
