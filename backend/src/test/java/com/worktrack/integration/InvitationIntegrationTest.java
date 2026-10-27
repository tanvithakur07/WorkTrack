package com.worktrack.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.worktrack.entity.AccountToken;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.Notification;
import com.worktrack.entity.NotificationStatus;
import com.worktrack.entity.NotificationType;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;
import com.worktrack.mail.EmailSender;
import com.worktrack.mail.OutboundEmail;
import com.worktrack.service.NotificationDispatcher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Employee invitation and first-login password setup, end to end on H2. The mail system is a mock. */
class InvitationIntegrationTest extends IntegrationTestBase {

    private static final Pattern TOKEN = Pattern.compile("setup-password\\.html#token=([A-Za-z0-9_-]+)");

    @MockitoBean
    private EmailSender emailSender;
    @Autowired
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void mockSender() {
        reset(emailSender);
        when(emailSender.channel()).thenReturn("SMTP");
    }

    private Map<String, Object> newEmployee(String email, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", "Carla Newhire");
        body.put("email", email);
        body.put("password", "Welcome-123");
        body.put("department", "Finance");
        body.put("jobTitle", "Analyst");
        body.put("joiningDate", "2026-02-02");
        body.put("role", "EMPLOYEE");
        body.put("status", status);
        return body;
    }

    private long createCarla(MockHttpSession adminSession) throws Exception {
        String response = mockMvc.perform(jsonPost("/api/employees", adminSession, newEmployee("carla@example.com", "ACTIVE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invitation").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private List<Notification> invitations() {
        return notificationRepository.findAll().stream().filter(n -> n.getType() == NotificationType.EMPLOYEE_INVITED).toList();
    }

    /** Sends everything that is due and returns the link found in the last e-mail that went out. */
    private String sendAndExtractToken() {
        dispatcher.processDue();
        ArgumentCaptor<OutboundEmail> sent = ArgumentCaptor.forClass(OutboundEmail.class);
        verify(emailSender, atLeastOnce()).send(sent.capture());
        OutboundEmail mail = sent.getAllValues().stream()
                .filter(m -> m.text().contains("setup-password.html#token=")).reduce((first, second) -> second).orElseThrow();
        Matcher matcher = TOKEN.matcher(mail.text());
        assertThat(matcher.find()).isTrue();
        assertThat(mail.html()).contains("setup-password.html#token=" + matcher.group(1));
        return matcher.group(1);
    }

    private void setupPassword(String token, String password, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/auth/setup-password").with(csrfToken()).contentType("application/json")
                        .content(json(Map.of("token", token, "password", password))))
                .andExpect(status().is(expectedStatus));
    }

    private int loginStatus(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(csrfToken()).contentType("application/json")
                .content(json(Map.of("email", email, "password", password)))).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("creating an employee queues exactly one invitation to the saved address; the outbox holds no link or password")
    void createQueuesOneInvitation() throws Exception {
        createCarla(login(admin));

        assertThat(invitations()).hasSize(1);
        Notification n = invitations().get(0);
        assertThat(n.getRecipientEmail()).isEqualTo("carla@example.com");
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getBodyText()).contains("Carla Newhire").contains("/login.html").doesNotContain("token=").doesNotContain("Welcome-123");
        assertThat(n.getBodyHtml()).doesNotContain("token=").doesNotContain("Welcome-123");
        assertThat(accountTokenRepository.count()).as("no secret exists until the mail is sent").isZero();
    }

    @Test
    @DisplayName("a refused creation (duplicate address, invalid body) queues nothing")
    void failedCreationQueuesNothing() throws Exception {
        MockHttpSession adminSession = login(admin);
        mockMvc.perform(jsonPost("/api/employees", adminSession, newEmployee("alice@example.com", "ACTIVE")))
                .andExpect(status().isConflict());
        Map<String, Object> invalid = newEmployee("carla@example.com", "ACTIVE");
        invalid.put("fullName", "");
        mockMvc.perform(jsonPost("/api/employees", adminSession, invalid)).andExpect(status().isBadRequest());

        assertThat(notificationRepository.count()).isZero();
        assertThat(userRepository.findByEmailIgnoreCase("carla@example.com")).isEmpty();
    }

    @Test
    @DisplayName("an inactive account is created without an invitation, and the admin is told so")
    void inactiveAccountGetsNoInvitation() throws Exception {
        mockMvc.perform(jsonPost("/api/employees", login(admin), newEmployee("carla@example.com", "INACTIVE")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.invitation").value("SKIPPED_INACTIVE_ACCOUNT"));
        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    @DisplayName("the e-mailed link lets the employee choose a password once: it works, replaces the admin's password, and cannot be reused")
    void fullInvitationFlow() throws Exception {
        createCarla(login(admin));
        String token = sendAndExtractToken();

        assertThat(notificationRepository.findAll().get(0).getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notificationRepository.findAll().get(0).getBodyHtml()).as("the secret is not stored in the outbox").doesNotContain(token);
        assertThat(accountTokenRepository.findAll()).singleElement()
                .satisfies(t -> assertThat(t.getTokenHash()).hasSize(64).isNotEqualTo(token));

        setupPassword(token, "My-own-pass7", 204);

        assertThat(loginStatus("carla@example.com", "My-own-pass7")).isEqualTo(200);
        assertThat(loginStatus("carla@example.com", "Welcome-123")).isEqualTo(401);
        setupPassword(token, "Another-pass8", 400); // single use
        assertThat(auditEventRepository.findAll()).anyMatch(e -> "PASSWORD_SETUP_COMPLETED".equals(e.getAction()));
    }

    @Test
    @DisplayName("unknown, expired and malformed requests are refused with one generic message; CSRF is required")
    void invalidSetupRequests() throws Exception {
        User carla = createUser("Carla Newhire", "carla@example.com", Role.EMPLOYEE, "Finance");
        String expiredSecret = "expired-secret-value-0123456789abcdefghijklmnop";
        accountTokenRepository.save(new AccountToken(carla.getId(), sha256(expiredSecret), "INVITATION",
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600)));

        setupPassword("not-a-real-token", "My-own-pass7", 400);
        setupPassword(expiredSecret, "My-own-pass7", 400);
        setupPassword("anything", "short", 400); // password rule
        mockMvc.perform(post("/api/auth/setup-password").contentType("application/json")
                .content(json(Map.of("token", "x", "password", "My-own-pass7")))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/setup-password").with(csrfToken()).contentType("application/json")
                        .content(json(Map.of("token", "not-a-real-token", "password", "My-own-pass7"))))
                .andExpect(jsonPath("$.message").value("This link is invalid or has expired. Ask your administrator to send you a new invitation."));
        assertThat(loginStatus("carla@example.com", PASSWORD)).isEqualTo(200); // untouched
    }

    @Test
    @DisplayName("resending: refused while one is still waiting; afterwards a new link replaces the old one; employees may not")
    void resend() throws Exception {
        MockHttpSession adminSession = login(admin);
        long id = createCarla(adminSession);

        mockMvc.perform(post("/api/employees/" + id + "/invitation").session(adminSession).with(csrfToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invitation").value("ALREADY_QUEUED"));
        assertThat(invitations()).hasSize(1);

        String first = sendAndExtractToken();
        reset(emailSender);
        when(emailSender.channel()).thenReturn("SMTP");
        mockMvc.perform(post("/api/employees/" + id + "/invitation").session(adminSession).with(csrfToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invitation").value("QUEUED"));
        assertThat(invitations()).hasSize(2);
        String second = sendAndExtractToken();

        assertThat(second).isNotEqualTo(first);
        setupPassword(first, "My-own-pass7", 400);  // replaced by the newer link
        setupPassword(second, "My-own-pass7", 204);

        mockMvc.perform(post("/api/employees/" + id + "/invitation").session(login(alice)).with(csrfToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/employees/" + id + "/invitation").session(adminSession)).andExpect(status().isForbidden()); // no CSRF
    }

    @Test
    @DisplayName("if the account is deactivated before the invitation is sent, nothing is sent and the message fails permanently")
    void deactivatedBeforeSending() throws Exception {
        MockHttpSession adminSession = login(admin);
        long id = createCarla(adminSession);
        User carla = userRepository.findById(id).orElseThrow();
        carla.setStatus(EmploymentStatus.INACTIVE);
        userRepository.save(carla);

        dispatcher.processDue();

        verify(emailSender, org.mockito.Mockito.never()).send(any());
        assertThat(invitations().get(0).getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(accountTokenRepository.count()).isZero();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
