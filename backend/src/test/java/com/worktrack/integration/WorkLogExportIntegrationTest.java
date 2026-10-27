package com.worktrack.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.worktrack.entity.DailyWorkLog;
import com.worktrack.entity.User;
import com.worktrack.entity.WorkLogStatus;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class WorkLogExportIntegrationTest extends IntegrationTestBase {

    private final LocalDate yesterday = LocalDate.now().minusDays(1);

    private DailyWorkLog saveLog(User owner, String title, String description, WorkLogStatus status) {
        return workLogRepository.save(new DailyWorkLog(owner, yesterday, title, description, new BigDecimal("2.50"), status));
    }

    private String csv(String url, MockHttpSession session) throws Exception {
        byte[] bytes = mockMvc.perform(get(url).session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("admin CSV: UTF-8 BOM, header row, one line per submitted report, drafts never included")
    void adminCsv() throws Exception {
        saveLog(alice, "Call leads", "Called 12 prospects", WorkLogStatus.COMPLETED);
        saveLog(bob, "Demo", "Product demo", WorkLogStatus.IN_PROGRESS);
        saveLog(alice, "Secret draft", "private", WorkLogStatus.DRAFT);

        var result = mockMvc.perform(get("/api/work-logs/export").session(login(admin))).andExpect(status().isOk()).andReturn();
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

        assertThat(body).startsWith("﻿Employee ID,Employee name,Department,Work date,Task title");
        assertThat(body).contains("Call leads").contains("Demo").doesNotContain("Secret draft");
        assertThat(body.trim().split("\r\n")).hasSize(3); // header + 2
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("attachment").contains(".csv");
    }

    @Test
    @DisplayName("the export never contains e-mail addresses or password hashes")
    void noPersonalDataBeyondTheReport() throws Exception {
        saveLog(alice, "Call leads", "x", WorkLogStatus.COMPLETED);

        String body = csv("/api/work-logs/export", login(admin));

        assertThat(body).doesNotContain("@example.com").doesNotContain("$2a$");
    }

    @Test
    @DisplayName("text that starts like a formula is neutralised so Excel cannot run it")
    void formulaInjection() throws Exception {
        saveLog(alice, "=HYPERLINK(\"http://evil\",\"x\")", "+cmd|' /C calc'!A0", WorkLogStatus.COMPLETED);

        String body = csv("/api/work-logs/export", login(admin));

        assertThat(body).contains("'=HYPERLINK").contains("'+cmd");
        assertThat(body).doesNotContain(",=HYPERLINK").doesNotContain(",\"=HYPERLINK");
    }

    @Test
    @DisplayName("filters apply to the export (employee, status, date range)")
    void filters() throws Exception {
        saveLog(alice, "Alice task", "x", WorkLogStatus.COMPLETED);
        saveLog(bob, "Bob task", "x", WorkLogStatus.BLOCKED);
        MockHttpSession adminSession = login(admin);

        assertThat(csv("/api/work-logs/export?employeeId=" + alice.getId(), adminSession)).contains("Alice task").doesNotContain("Bob task");
        assertThat(csv("/api/work-logs/export?status=BLOCKED", adminSession)).contains("Bob task").doesNotContain("Alice task");
        assertThat(csv("/api/work-logs/export?from=" + yesterday.plusDays(1) , adminSession)).doesNotContain("Alice task").doesNotContain("Bob task");
    }

    @Test
    @DisplayName("an export with no matching reports is a valid file with only the header")
    void emptyExport() throws Exception {
        String body = csv("/api/work-logs/export", login(admin));
        assertThat(body.trim().split("\r\n")).hasSize(1);
    }

    @Test
    @DisplayName("an employee's own export contains only their own submitted reports")
    void myExport() throws Exception {
        saveLog(alice, "Alice task", "x", WorkLogStatus.COMPLETED);
        saveLog(bob, "Bob task", "x", WorkLogStatus.COMPLETED);

        String body = csv("/api/work-logs/my/export", login(alice));

        assertThat(body).contains("Alice task").doesNotContain("Bob task");
    }

    @Test
    @DisplayName("the admin export is forbidden to employees and refused without login; bad input is a 400")
    void accessAndValidation() throws Exception {
        mockMvc.perform(get("/api/work-logs/export")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/work-logs/my/export")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/work-logs/export").session(login(alice))).andExpect(status().isForbidden());
        MockHttpSession adminSession = login(admin);
        mockMvc.perform(get("/api/work-logs/export?format=pdf").session(adminSession)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/work-logs/export?from=2026-03-05&to=2026-03-01").session(adminSession)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/work-logs/export?status=NOPE").session(adminSession)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("XLSX export is a real zip package with the sheet, content-type and no formulas")
    void xlsx() throws Exception {
        saveLog(alice, "=SUM(A1)", "x", WorkLogStatus.COMPLETED);

        var result = mockMvc.perform(get("/api/work-logs/export?format=xlsx").session(login(admin))).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentType()).contains("spreadsheetml.sheet");
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains(".xlsx");
        String sheet = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    sheet = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(sheet).isNotNull().contains("Alice Employee").doesNotContain("<f>");
        assertThat(sheet).containsAnyOf("'=SUM(A1)", "&apos;=SUM(A1)");
    }
}
