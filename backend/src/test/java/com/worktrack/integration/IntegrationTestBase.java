package com.worktrack.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveType;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;
import com.worktrack.repository.AccountTokenRepository;
import com.worktrack.repository.AuditEventRepository;
import com.worktrack.repository.DailyWorkLogRepository;
import com.worktrack.repository.NotificationRepository;
import com.worktrack.repository.LeaveRequestRepository;
import com.worktrack.repository.UserRepository;
import com.worktrack.security.LoginAttemptLimiter;

/**
 * Starts the whole application (security filters, controllers, services, JPA) against an in-memory
 * H2 database and drives it over MockMvc exactly like a browser would: real login, real session
 * cookie, real CSRF check. Every test starts from the same three users.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    protected static final String PASSWORD = "Passw0rd1";

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected LeaveRequestRepository leaveRepository;
    @Autowired
    protected DailyWorkLogRepository workLogRepository;
    @Autowired
    protected NotificationRepository notificationRepository;
    @Autowired
    protected AuditEventRepository auditEventRepository;
    @Autowired
    protected AccountTokenRepository accountTokenRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    protected LoginAttemptLimiter loginAttemptLimiter;

    protected User admin;
    protected User alice;
    protected User bob;

    @BeforeEach
    void seedUsers() {
        // The limiter is a singleton shared by every test in this Spring context; start each test clean.
        loginAttemptLimiter.reset();
        notificationRepository.deleteAll();
        accountTokenRepository.deleteAll();
        auditEventRepository.deleteAll();
        workLogRepository.deleteAll(); // work logs reference users, so they go first
        leaveRepository.deleteAll();
        userRepository.deleteAll();
        admin = createUser("Ada Admin", "ada@example.com", Role.ADMIN, "Administration");
        alice = createUser("Alice Employee", "alice@example.com", Role.EMPLOYEE, "Engineering");
        bob = createUser("Bob Employee", "bob@example.com", Role.EMPLOYEE, "Sales");
    }

    protected User createUser(String name, String email, Role role, String department) {
        User user = new User();
        user.setFullName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setDepartment(department);
        user.setJobTitle("Specialist");
        user.setJoiningDate(LocalDate.of(2024, 1, 15));
        user.setRole(role);
        user.setStatus(EmploymentStatus.ACTIVE);
        return userRepository.save(user);
    }

    /** A leave request written straight to the database, bypassing the API rules. */
    protected LeaveRequest saveLeave(User owner, LeaveType type, LocalDate start, LocalDate end) {
        return leaveRepository.save(new LeaveRequest(owner, type, start, end, 3, "Test reason"));
    }

    /** Logs in through the real endpoint and returns the session the server created. */
    protected MockHttpSession login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /**
     * Sends a CSRF token the same way api.js does: the XSRF-TOKEN cookie plus the same value in the
     * X-XSRF-TOKEN header. (spring-security-test's csrf().asHeader() sends X-CSRF-TOKEN, which this
     * application's cookie-based configuration deliberately does not read.)
     */
    public static RequestPostProcessor csrfToken() {
        return request -> {
            String token = java.util.UUID.randomUUID().toString();
            java.util.List<jakarta.servlet.http.Cookie> cookies = new java.util.ArrayList<>();
            if (request.getCookies() != null) {
                cookies.addAll(java.util.Arrays.asList(request.getCookies()));
            }
            cookies.add(new jakarta.servlet.http.Cookie("XSRF-TOKEN", token));
            request.setCookies(cookies.toArray(new jakarta.servlet.http.Cookie[0]));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    protected MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.with(csrfToken());
    }

    protected MockHttpServletRequestBuilder jsonPost(String url, MockHttpSession session, Object body) throws Exception {
        return post(url).session(session).with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    protected MockHttpServletRequestBuilder jsonPatch(String url, MockHttpSession session, Object body) throws Exception {
        return patch(url).session(session).with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    /** A Monday at least two weeks away, so "start date must not be in the past" never interferes. */
    protected static LocalDate futureMonday() {
        return LocalDate.now().plusDays(14).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }
}
