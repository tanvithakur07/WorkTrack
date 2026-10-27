package com.worktrack.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

/** Brute-force protection on the sign-in endpoint, through the real security chain and controller. */
class LoginThrottlingIntegrationTest extends IntegrationTestBase {

    private static final String ATTACKER_IP = "203.0.113.7";

    private ResultActions attempt(String email, String password, String ip) throws Exception {
        return mockMvc.perform(withCsrf(post("/api/auth/login"))
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password))));
    }

    @Test
    @DisplayName("after five wrong passwords the sixth attempt is a 429 with Retry-After, even with the right password")
    void locksAfterRepeatedFailures() throws Exception {
        for (int i = 0; i < 5; i++) {
            attempt("alice@example.com", "wrong-pass-" + i, ATTACKER_IP).andExpect(status().isUnauthorized());
        }

        attempt("alice@example.com", PASSWORD, ATTACKER_IP)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.message").value(containsString("Too many sign-in attempts")))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    @DisplayName("an unknown email is throttled in exactly the same way, so the limiter does not reveal accounts")
    void unknownAndKnownEmailsBehaveIdentically() throws Exception {
        String knownMessage = null;
        String unknownMessage = null;
        for (int i = 0; i < 5; i++) {
            attempt("alice@example.com", "wrong-pass-" + i, "203.0.113.10").andExpect(status().isUnauthorized());
            attempt("ghost@example.com", "wrong-pass-" + i, "203.0.113.11").andExpect(status().isUnauthorized());
        }
        knownMessage = attempt("alice@example.com", "wrong-pass-x", "203.0.113.10")
                .andExpect(status().isTooManyRequests()).andReturn().getResponse().getContentAsString();
        unknownMessage = attempt("ghost@example.com", "wrong-pass-x", "203.0.113.11")
                .andExpect(status().isTooManyRequests()).andReturn().getResponse().getContentAsString();

        assertThat(messageOf(knownMessage)).isEqualTo(messageOf(unknownMessage));
        assertThat(knownMessage).doesNotContain("alice").doesNotContain("ghost");
    }

    @Test
    @DisplayName("a lock on one address does not lock the real owner out when they sign in from elsewhere")
    void attackerCannotLockOutTheOwner() throws Exception {
        for (int i = 0; i < 5; i++) {
            attempt("alice@example.com", "guess-" + i, ATTACKER_IP).andExpect(status().isUnauthorized());
        }
        attempt("alice@example.com", PASSWORD, ATTACKER_IP).andExpect(status().isTooManyRequests());

        attempt("alice@example.com", PASSWORD, "198.51.100.20").andExpect(status().isOk());
    }

    @Test
    @DisplayName("typos below the limit never lock anyone, and a successful sign-in clears the counter")
    void successResetsFailures() throws Exception {
        for (int i = 0; i < 4; i++) {
            attempt("alice@example.com", "typo-" + i, "198.51.100.30").andExpect(status().isUnauthorized());
        }
        attempt("alice@example.com", PASSWORD, "198.51.100.30").andExpect(status().isOk());

        for (int i = 0; i < 4; i++) {
            attempt("alice@example.com", "typo-again-" + i, "198.51.100.30").andExpect(status().isUnauthorized());
        }
        attempt("alice@example.com", PASSWORD, "198.51.100.30").andExpect(status().isOk());
    }

    @Test
    @DisplayName("a blank body is a validation error and does not count as a failed sign-in")
    void validationErrorsDoNotCount() throws Exception {
        for (int i = 0; i < 8; i++) {
            mockMvc.perform(withCsrf(post("/api/auth/login"))
                            .with(request -> {
                                request.setRemoteAddr("198.51.100.40");
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"\",\"password\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }
        attempt("alice@example.com", PASSWORD, "198.51.100.40").andExpect(status().isOk());
    }

    @Test
    @DisplayName("guessing the current password through change-password is throttled the same way")
    void changePasswordGuessingIsThrottled() throws Exception {
        MockHttpSession session = login(alice);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(jsonPost("/api/auth/change-password", session,
                            Map.of("currentPassword", "guess-number-" + i, "newPassword", "Another-pass2")))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(jsonPost("/api/auth/change-password", session,
                        Map.of("currentPassword", PASSWORD, "newPassword", "Another-pass2")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.message").value(not(containsString("incorrect"))));
    }

    private String messageOf(String json) throws Exception {
        return objectMapper.readTree(json).get("message").asText();
    }
}
