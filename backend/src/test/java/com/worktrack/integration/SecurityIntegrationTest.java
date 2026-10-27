package com.worktrack.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveStatus;
import com.worktrack.entity.LeaveType;

/** Cross-cutting security behaviour: headers, cookies, sessions, authorization on every endpoint, leakage. */
class SecurityIntegrationTest extends IntegrationTestBase {

    @Autowired
    private Environment environment;

    /* ------------------------------ headers and cookies ------------------------------ */

    @Test
    @DisplayName("security headers are present: CSP, frame, nosniff, referrer, permissions, COOP/CORP, no-store")
    void securityHeaders() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-inline"))))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(header().string("Permissions-Policy", containsString("camera=()")))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    @DisplayName("over HTTPS the response carries Strict-Transport-Security")
    void hstsOnHttps() throws Exception {
        mockMvc.perform(get("/api/auth/csrf").secure(true))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=")));
    }

    @Test
    @DisplayName("the CSRF cookie is Secure and SameSite=Lax by default (production-safe default)")
    void csrfCookieAttributes() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/auth/csrf")).andReturn().getResponse();

        assertThat(response.getCookie("XSRF-TOKEN")).isNotNull();
        assertThat(response.getCookie("XSRF-TOKEN").getSecure()).isTrue();
        // Spring Security hands SameSite to the servlet container as a cookie attribute; MockMvc does not
        // always render it into the Set-Cookie header, so accept either form (a real container writes the header).
        String sameSiteAttribute = response.getCookie("XSRF-TOKEN").getAttribute("SameSite");
        boolean inHeader = response.getHeaders("Set-Cookie").stream()
                .anyMatch(h -> h.contains("XSRF-TOKEN") && h.contains("SameSite=Lax"));
        assertThat(inHeader || "Lax".equalsIgnoreCase(sameSiteAttribute))
                .as("SameSite=Lax (attribute=%s, headers=%s)", sameSiteAttribute, response.getHeaders("Set-Cookie"))
                .isTrue();
    }

    @Test
    @DisplayName("session cookie configuration defaults: HttpOnly, SameSite=Lax, Secure, cookie-only tracking, 30 minutes")
    void sessionCookieDefaults() {
        assertThat(environment.getProperty("server.servlet.session.cookie.http-only")).isEqualTo("true");
        assertThat(environment.getProperty("server.servlet.session.cookie.same-site")).isEqualToIgnoringCase("lax");
        assertThat(environment.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(environment.getProperty("server.servlet.session.tracking-modes")).isEqualTo("cookie");
        assertThat(environment.getProperty("server.servlet.session.timeout")).isEqualTo("30m");
        assertThat(environment.getProperty("server.servlet.session.cookie.name")).isEqualTo("WORKTRACK_SESSION");
    }

    /* ------------------------------ sessions ------------------------------ */

    @Test
    @DisplayName("signing in with a pre-existing session id issues a new id (session fixation)")
    void loginRotatesSessionId() throws Exception {
        MockHttpSession planted = new MockHttpSession();
        String before = planted.getId();

        mockMvc.perform(post("/api/auth/login").session(planted).with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "alice@example.com", "password", PASSWORD))))
                .andExpect(status().isOk());

        assertThat(planted.getId()).isNotEqualTo(before);
    }

    @Test
    @DisplayName("changing the password ends every OTHER session of that person but keeps the current one")
    void passwordChangeEndsOtherSessions() throws Exception {
        MockHttpSession thisBrowser = login(alice);
        MockHttpSession stolenOrOld = login(alice);
        mockMvc.perform(get("/api/auth/me").session(stolenOrOld)).andExpect(status().isOk());

        mockMvc.perform(jsonPost("/api/auth/change-password", thisBrowser,
                        Map.of("currentPassword", PASSWORD, "newPassword", "Another-pass2")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").session(thisBrowser)).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").session(stolenOrOld)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("when an administrator resets an employee's password their open sessions stop working")
    void adminPasswordResetEndsSessions() throws Exception {
        MockHttpSession bobsSession = login(bob);
        MockHttpSession adminSession = login(admin);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("fullName", bob.getFullName());
        update.put("email", bob.getEmail());
        update.put("password", "Reset-pass-9");
        update.put("department", bob.getDepartment());
        update.put("jobTitle", bob.getJobTitle());
        update.put("joiningDate", bob.getJoiningDate().toString());
        update.put("role", "EMPLOYEE");
        update.put("status", "ACTIVE");

        mockMvc.perform(withCsrf(put("/api/employees/" + bob.getId())).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").session(bobsSession)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("demoting an administrator ends their admin session instead of leaving stale privileges")
    void demotionEndsAdminSession() throws Exception {
        var secondAdmin = createUser("Second Admin", "second@example.com",
                com.worktrack.entity.Role.ADMIN, "Administration");
        MockHttpSession secondSession = login(secondAdmin);
        MockHttpSession adminSession = login(admin);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("fullName", secondAdmin.getFullName());
        update.put("email", secondAdmin.getEmail());
        update.put("department", secondAdmin.getDepartment());
        update.put("jobTitle", secondAdmin.getJobTitle());
        update.put("joiningDate", secondAdmin.getJoiningDate().toString());
        update.put("role", "EMPLOYEE");
        update.put("status", "ACTIVE");

        mockMvc.perform(withCsrf(put("/api/employees/" + secondAdmin.getId())).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/employees").session(secondSession)).andExpect(status().isUnauthorized());
    }

    /* ------------------------------ authorization on every endpoint ------------------------------ */

    private record Call(String method, String url) {
        MockHttpServletRequestBuilder builder() {
            return switch (method) {
                case "GET" -> get(url);
                case "POST" -> post(url);
                case "PUT" -> put(url);
                case "PATCH" -> patch(url);
                case "DELETE" -> delete(url);
                default -> throw new IllegalArgumentException(method);
            };
        }
    }

    private static final List<Call> ALL_PROTECTED_CALLS = List.of(
            new Call("GET", "/api/auth/me"),
            new Call("POST", "/api/auth/change-password"),
            new Call("GET", "/api/employees"),
            new Call("GET", "/api/employees/departments"),
            new Call("GET", "/api/employees/1"),
            new Call("POST", "/api/employees"),
            new Call("PUT", "/api/employees/1"),
            new Call("DELETE", "/api/employees/1"),
            new Call("GET", "/api/leaves"),
            new Call("GET", "/api/leaves/my"),
            new Call("GET", "/api/leaves/1"),
            new Call("POST", "/api/leaves"),
            new Call("PATCH", "/api/leaves/1/cancel"),
            new Call("PATCH", "/api/leaves/1/approve"),
            new Call("PATCH", "/api/leaves/1/reject"),
            new Call("GET", "/api/dashboard/admin"),
            new Call("GET", "/api/dashboard/employee"),
            new Call("GET", "/api/work-logs"),
            new Call("GET", "/api/work-logs/my"),
            new Call("GET", "/api/work-logs/my/summary"),
            new Call("GET", "/api/work-logs/summary"),
            new Call("GET", "/api/work-logs/missing"),
            new Call("GET", "/api/work-logs/1"),
            new Call("POST", "/api/work-logs"),
            new Call("PUT", "/api/work-logs/1"),
            new Call("DELETE", "/api/work-logs/1"),
            new Call("GET", "/api/work-logs/export"),
            new Call("GET", "/api/work-logs/my/export"),
            new Call("GET", "/api/dashboard/employee/analytics"),
            new Call("GET", "/api/dashboard/admin/analytics"),
            new Call("GET", "/api/audit"),
            new Call("GET", "/api/audit/actions"),
            new Call("GET", "/api/notifications"),
            new Call("GET", "/api/notifications/summary"),
            new Call("POST", "/api/notifications/1/retry"));

    private static final List<Call> ADMIN_ONLY_CALLS = List.of(
            new Call("GET", "/api/employees"),
            new Call("GET", "/api/employees/departments"),
            new Call("POST", "/api/employees"),
            new Call("PUT", "/api/employees/1"),
            new Call("DELETE", "/api/employees/1"),
            new Call("GET", "/api/leaves"),
            new Call("PATCH", "/api/leaves/1/approve"),
            new Call("PATCH", "/api/leaves/1/reject"),
            new Call("GET", "/api/dashboard/admin"),
            new Call("GET", "/api/work-logs"),
            new Call("GET", "/api/work-logs/summary"),
            new Call("GET", "/api/work-logs/missing"),
            new Call("GET", "/api/work-logs/export"),
            new Call("GET", "/api/dashboard/admin/analytics"),
            new Call("GET", "/api/audit"),
            new Call("GET", "/api/audit/actions"),
            new Call("GET", "/api/notifications"),
            new Call("GET", "/api/notifications/summary"),
            new Call("POST", "/api/notifications/1/retry"));

    @Test
    @DisplayName("every protected endpoint answers 401 JSON without a session (CSRF token present, so it is not a 403)")
    void everyEndpointRequiresSignIn() throws Exception {
        for (Call call : ALL_PROTECTED_CALLS) {
            mockMvc.perform(call.builder().with(csrfHeader()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    @Test
    @DisplayName("every administrator endpoint answers 403 to an ordinary employee, whatever the body")
    void adminEndpointsAreForbiddenToEmployees() throws Exception {
        MockHttpSession session = login(alice);
        for (Call call : ADMIN_ONLY_CALLS) {
            mockMvc.perform(call.builder().session(session).with(csrfHeader())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("every state-changing endpoint refuses a signed-in request that has no CSRF token")
    void everyWriteNeedsCsrfToken() throws Exception {
        MockHttpSession session = login(admin);
        for (Call call : ALL_PROTECTED_CALLS) {
            if (call.method().equals("GET")) {
                continue;
            }
            mockMvc.perform(call.builder().session(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("a wrong CSRF token is refused as well")
    void wrongCsrfTokenIsRefused() throws Exception {
        MockHttpSession session = login(alice);
        mockMvc.perform(post("/api/leaves").session(session).header("X-XSRF-TOKEN", "forged-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    /* ------------------------------ data ownership ------------------------------ */

    @Test
    @DisplayName("extra JSON fields cannot be used to approve your own request or file it for someone else")
    void massAssignmentIsIgnored() throws Exception {
        LocalDate monday = futureMonday();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("leaveType", "CASUAL");
        body.put("startDate", monday.toString());
        body.put("endDate", monday.plusDays(1).toString());
        body.put("reason", "Sneaky request");
        body.put("status", "APPROVED");
        body.put("employeeId", bob.getId());
        body.put("reviewedBy", admin.getId());
        body.put("days", 99);

        mockMvc.perform(jsonPost("/api/leaves", login(alice), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.employeeId").value(alice.getId()))
                .andExpect(jsonPath("$.reviewedById").doesNotExist())
                .andExpect(jsonPath("$.days").value(2));
    }

    @Test
    @DisplayName("an employee cannot read, cancel or review another employee's request (403) and cannot see it in any list")
    void employeesAreIsolatedFromEachOther() throws Exception {
        LocalDate monday = futureMonday();
        LeaveRequest bobsLeave = saveLeave(bob, LeaveType.CASUAL, monday, monday.plusDays(1));
        MockHttpSession aliceSession = login(alice);

        mockMvc.perform(get("/api/leaves/" + bobsLeave.getId()).session(aliceSession)).andExpect(status().isForbidden());
        mockMvc.perform(withCsrf(patch("/api/leaves/" + bobsLeave.getId() + "/cancel")).session(aliceSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/leaves/my").session(aliceSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        // asking for someone else's data through the query string changes nothing
        mockMvc.perform(get("/api/leaves/my").param("employeeId", String.valueOf(bob.getId())).session(aliceSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/employees/" + bob.getId()).session(aliceSession)).andExpect(status().isForbidden());

        assertThat(leaveRepository.findById(bobsLeave.getId()).orElseThrow().getStatus())
                .isEqualTo(LeaveStatus.PENDING);
    }

    /* ------------------------------ validation and leakage ------------------------------ */

    @Test
    @DisplayName("a password over BCrypt's 72-byte limit is a clear 400, not a 500 (characters are not bytes)")
    void multiByteLongPasswordIsRejected() throws Exception {
        String tooManyBytes = "Aa1" + "é".repeat(40);      // 43 characters, 83 bytes
        MockHttpSession session = login(alice);

        mockMvc.perform(jsonPost("/api/auth/change-password", session,
                        Map.of("currentPassword", PASSWORD, "newPassword", tooManyBytes)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("too long")));
    }

    @Test
    @DisplayName("malformed JSON, unknown paths and wrong methods give clean JSON errors without internals")
    void errorsDoNotLeakInternals() throws Exception {
        MockHttpSession session = login(alice);

        mockMvc.perform(post("/api/leaves").session(session).with(csrfHeader())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(not(containsString("Exception"))))
                .andExpect(jsonPath("$.message").value(not(containsString("com.fasterxml"))));

        String notFound = mockMvc.perform(get("/api/does-not-exist").session(session))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(notFound).doesNotContain("org.springframework").doesNotContain("Exception").doesNotContain("at com.");

        mockMvc.perform(delete("/api/leaves/1").session(session).with(csrfHeader()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("responses never contain password hashes, for employees, leaves or dashboards")
    void noHashesInAnyResponse() throws Exception {
        MockHttpSession adminSession = login(admin);
        for (String url : List.of("/api/employees", "/api/employees/" + alice.getId(), "/api/auth/me",
                "/api/dashboard/admin", "/api/leaves")) {
            String body = mockMvc.perform(get(url).session(adminSession)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("$2a$").doesNotContain("$2b$").doesNotContain("passwordHash")
                    .doesNotContain("password_hash");
        }
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor csrfHeader() {
        return csrfToken();
    }
}
