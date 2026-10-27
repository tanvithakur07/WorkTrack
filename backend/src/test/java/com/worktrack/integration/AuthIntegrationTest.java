package com.worktrack.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import com.worktrack.entity.EmploymentStatus;

class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("valid credentials sign in, and the response never contains a password or hash")
    void loginSucceeds() throws Exception {
        mockMvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "ADA@example.com", "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("a wrong password is a 401 with a generic message")
    void wrongPassword() throws Exception {
        mockMvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "ada@example.com", "password", "not-the-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("an unknown email gets exactly the same 401, so accounts cannot be probed")
    void unknownEmail() throws Exception {
        mockMvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "nobody@example.com", "password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("a deactivated account cannot sign in")
    void inactiveAccountCannotLogin() throws Exception {
        bob.setStatus(EmploymentStatus.INACTIVE);
        userRepository.save(bob);

        mockMvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "bob@example.com", "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a blank login body fails validation with field errors")
    void blankLoginIsValidated() throws Exception {
        mockMvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "", "password", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    @DisplayName("protected APIs answer 401 JSON (not a redirect) without a session")
    void unauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mockMvc.perform(get("/api/employees")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/leaves/my")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dashboard/admin")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("state-changing requests without a CSRF token are refused with 403")
    void csrfIsEnforced() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "ada@example.com", "password", PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("CSRF")));
    }

    @Test
    @DisplayName("/api/auth/me returns the signed-in person")
    void meReturnsCurrentUser() throws Exception {
        MockHttpSession session = login(alice);

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"));
    }

    @Test
    @DisplayName("logout ends the session: the same cookie no longer works")
    void logoutEndsSession() throws Exception {
        MockHttpSession session = login(alice);

        mockMvc.perform(withCsrf(post("/api/auth/logout")).session(session))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a session stops working as soon as the account is deactivated")
    void deactivationEndsExistingSessions() throws Exception {
        MockHttpSession session = login(bob);
        bob.setStatus(EmploymentStatus.INACTIVE);
        userRepository.save(bob);

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("changing the password needs the current password, and the new one works for the next login")
    void changePassword() throws Exception {
        MockHttpSession session = login(alice);

        mockMvc.perform(jsonPost("/api/auth/change-password", session,
                        Map.of("currentPassword", "wrong-pass1", "newPassword", "Another-pass2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));

        mockMvc.perform(jsonPost("/api/auth/change-password", session,
                        Map.of("currentPassword", PASSWORD, "newPassword", "Another-pass2")))
                .andExpect(status().isNoContent());

        mockMvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "alice@example.com", "password", "Another-pass2"))))
                .andExpect(status().isOk());
        mockMvc.perform(withCsrf(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "alice@example.com", "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("stored passwords are BCrypt hashes, never plaintext")
    void passwordsAreHashed() {
        String hash = userRepository.findByEmailIgnoreCase("ada@example.com").orElseThrow().getPasswordHash();
        org.assertj.core.api.Assertions.assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD);
    }
}
