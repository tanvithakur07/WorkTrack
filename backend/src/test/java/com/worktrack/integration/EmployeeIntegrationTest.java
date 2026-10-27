package com.worktrack.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveStatus;
import com.worktrack.entity.LeaveType;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;

class EmployeeIntegrationTest extends IntegrationTestBase {

    private Map<String, Object> newEmployee(String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", "Carla Newhire");
        body.put("email", email);
        body.put("password", "Welcome-123");
        body.put("department", "Finance");
        body.put("jobTitle", "Analyst");
        body.put("joiningDate", "2026-02-02");
        body.put("role", "EMPLOYEE");
        body.put("status", "ACTIVE");
        return body;
    }

    /* ------------------------------ create ------------------------------ */

    @Test
    @DisplayName("an administrator can create an employee: 201, Location header, derived ID, lower-cased email")
    void adminCreatesEmployee() throws Exception {
        MockHttpSession session = login(admin);

        mockMvc.perform(jsonPost("/api/employees", session, newEmployee("Carla.New@Example.com")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/employees/")))
                .andExpect(jsonPath("$.email").value("carla.new@example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.employeeCode").value(startsWith("EMP-")))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User saved = userRepository.findByEmailIgnoreCase("carla.new@example.com").orElseThrow();
        assertThat(saved.getPasswordHash()).startsWith("$2");
    }

    @Test
    @DisplayName("the new employee can sign in with the temporary password")
    void newEmployeeCanLogin() throws Exception {
        MockHttpSession session = login(admin);
        mockMvc.perform(jsonPost("/api/employees", session, newEmployee("carla@example.com")))
                .andExpect(status().isCreated());

        mockMvc.perform(withCsrf(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "carla@example.com", "password", "Welcome-123"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("invalid data is a 400 with a message per field")
    void createValidatesInput() throws Exception {
        MockHttpSession session = login(admin);
        Map<String, Object> body = newEmployee("not-an-email");
        body.put("fullName", "");
        body.put("password", "short");
        body.remove("joiningDate");

        mockMvc.perform(jsonPost("/api/employees", session, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.fullName").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andExpect(jsonPath("$.fieldErrors.joiningDate").exists());
    }

    @Test
    @DisplayName("a duplicate email (even with different capitalisation) is a 409")
    void duplicateEmailConflicts() throws Exception {
        MockHttpSession session = login(admin);

        mockMvc.perform(jsonPost("/api/employees", session, newEmployee("ALICE@example.com")))
                .andExpect(status().isConflict());
    }

    /* ------------------------------ read / search / paginate ------------------------------ */

    @Test
    @DisplayName("the list supports search, department and status filters")
    void listFilters() throws Exception {
        MockHttpSession session = login(admin);
        User carol = createUser("Carol Sales", "carol@example.com", Role.EMPLOYEE, "Sales");
        carol.setStatus(EmploymentStatus.INACTIVE);
        userRepository.save(carol);

        mockMvc.perform(get("/api/employees").session(session).param("q", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value("alice@example.com"));

        mockMvc.perform(get("/api/employees").session(session).param("department", "sales"))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/employees").session(session).param("status", "INACTIVE"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value("Carol Sales"));

        mockMvc.perform(get("/api/employees").session(session).param("q", "no-such-person"))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("the list is paginated and sortable on the server")
    void pagination() throws Exception {
        MockHttpSession session = login(admin);

        mockMvc.perform(get("/api/employees").session(session)
                        .param("size", "2").param("page", "0").param("sortBy", "fullName").param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].fullName").value("Ada Admin"))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/api/employees").session(session)
                        .param("size", "2").param("page", "1").param("sortBy", "fullName").param("direction", "asc"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("bad pagination or sort parameters are rejected, not passed to the database")
    void invalidPaginationParameters() throws Exception {
        MockHttpSession session = login(admin);

        mockMvc.perform(get("/api/employees").session(session).param("sortBy", "passwordHash"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/employees").session(session).param("size", "1000"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/employees").session(session).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/employees").session(session).param("status", "RETIRED"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown employee is a 404")
    void unknownEmployeeIs404() throws Exception {
        MockHttpSession session = login(admin);

        mockMvc.perform(get("/api/employees/999999").session(session)).andExpect(status().isNotFound());
    }

    /* ------------------------------ update ------------------------------ */

    @Test
    @DisplayName("an administrator can update an employee and reset their password")
    void adminUpdatesEmployee() throws Exception {
        MockHttpSession session = login(admin);
        Map<String, Object> body = newEmployee("alice@example.com");
        body.put("fullName", "Alice Promoted");
        body.put("jobTitle", "Lead");
        body.put("password", "Reset-Pass-9");
        body.put("status", "ACTIVE");

        mockMvc.perform(withCsrf(put("/api/employees/" + alice.getId())).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Alice Promoted"))
                .andExpect(jsonPath("$.jobTitle").value("Lead"));

        mockMvc.perform(withCsrf(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "alice@example.com", "password", "Reset-Pass-9"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an administrator cannot remove their own admin role or deactivate themselves")
    void adminCannotLockThemselvesOut() throws Exception {
        MockHttpSession session = login(admin);
        Map<String, Object> body = newEmployee("ada@example.com");
        body.put("role", "EMPLOYEE");
        body.put("status", "ACTIVE");
        body.remove("password");

        mockMvc.perform(withCsrf(put("/api/employees/" + admin.getId())).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isConflict());

        mockMvc.perform(withCsrf(delete("/api/employees/" + admin.getId())).session(session))
                .andExpect(status().isConflict());
        assertThat(userRepository.findById(admin.getId()).orElseThrow().getStatus()).isEqualTo(EmploymentStatus.ACTIVE);
    }

    /* ------------------------------ delete = deactivate ------------------------------ */

    @Test
    @DisplayName("deleting deactivates: the record stays, pending leave is cancelled, sign-in stops")
    void deactivationIsSafe() throws Exception {
        MockHttpSession session = login(admin);
        LocalDate monday = futureMonday();
        LeaveRequest pending = saveLeave(bob, LeaveType.CASUAL, monday, monday.plusDays(2));

        mockMvc.perform(withCsrf(delete("/api/employees/" + bob.getId())).session(session))
                .andExpect(status().isNoContent());

        User stored = userRepository.findById(bob.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(EmploymentStatus.INACTIVE);
        assertThat(leaveRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(LeaveStatus.CANCELLED);

        mockMvc.perform(withCsrf(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "bob@example.com", "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    /* ------------------------------ authorisation ------------------------------ */

    @Test
    @DisplayName("an employee cannot list, create, update or delete employees: 403")
    void employeeCannotManageEmployees() throws Exception {
        MockHttpSession session = login(alice);

        mockMvc.perform(get("/api/employees").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/employees/departments").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(jsonPost("/api/employees", session, newEmployee("x@example.com")))
                .andExpect(status().isForbidden());
        mockMvc.perform(withCsrf(put("/api/employees/" + bob.getId())).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(json(newEmployee("bob@example.com"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(withCsrf(delete("/api/employees/" + bob.getId())).session(session))
                .andExpect(status().isForbidden());
        assertThat(userRepository.findById(bob.getId()).orElseThrow().getStatus()).isEqualTo(EmploymentStatus.ACTIVE);
    }

    @Test
    @DisplayName("an employee can read their own profile but not another employee's")
    void employeeCanOnlyReadOwnProfile() throws Exception {
        MockHttpSession session = login(alice);

        mockMvc.perform(get("/api/employees/" + alice.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"));

        mockMvc.perform(get("/api/employees/" + bob.getId()).session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/employees/" + admin.getId()).session(session))
                .andExpect(status().isForbidden());
    }
}
