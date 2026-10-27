package com.worktrack.service;

import com.worktrack.entity.AuditOutcome;
import com.worktrack.entity.DailyWorkLog;
import com.worktrack.entity.User;
import com.worktrack.entity.WorkLogStatus;
import com.worktrack.exception.BadRequestException;
import com.worktrack.exception.ForbiddenOperationException;
import com.worktrack.export.CsvWriter;
import com.worktrack.export.XlsxWriter;
import com.worktrack.repository.DailyWorkLogRepository;
import com.worktrack.repository.WorkLogSpecifications;
import com.worktrack.security.CurrentUser;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * Exports daily work reports as CSV or Excel (.xlsx).
 *
 * Scope (enforced here, not only by URL rules):
 *  - exportAll: administrators only. Submitted entries of every employee; DRAFTS ARE NEVER INCLUDED,
 *    the same rule as the on-screen admin list.
 *  - exportMine: any signed-in user, but only their own entries (drafts included, they are theirs).
 *    The owner is the session user; no employee id from the client is used.
 *
 * Memory: rows are read in pages of 500 and written to the response immediately, so memory does not grow
 * with the result size. The row count is checked BEFORE anything is written and a request above
 * worktrack.export.max-rows is refused with a clear 400 (once bytes are streaming, an error can no longer be
 * reported as JSON).
 *
 * Content: only report data and the author's name, id and department. No e-mail address, password hash,
 * session or other account data is selected. Text cells are protected against formula injection
 * (see SpreadsheetSafe).
 */
@Service
public class WorkLogExportService {

    public enum Format { CSV, XLSX }

    static final List<String> HEADER = List.of("Employee ID", "Employee name", "Department", "Work date",
            "Task title", "Description", "Reported hours", "Status", "Created (UTC)", "Updated (UTC)");
    private static final int[] WIDTHS = {12, 24, 18, 12, 36, 60, 14, 14, 20, 20};
    private static final int PAGE_SIZE = 500;
    private static final Sort ORDER = Sort.by(Sort.Order.asc("workDate"), Sort.Order.asc("id"));

    private final DailyWorkLogRepository repository;
    private final AuditService auditService;
    private final Clock clock;
    private final long maxRows;

    public WorkLogExportService(DailyWorkLogRepository repository, AuditService auditService, Clock clock,
                                @Value("${worktrack.export.max-rows:50000}") long maxRows) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
        this.maxRows = maxRows;
    }

    /** Validates and counts WITHOUT writing anything. Call before touching the response. */
    public long prepareAll(CurrentUser actor, Long employeeId, String department, WorkLogStatus status,
                           LocalDate from, LocalDate to, String text) {
        if (!actor.isAdmin()) {
            throw new ForbiddenOperationException("Only administrators can export all work reports");
        }
        return prepare(specAll(employeeId, department, status, from, to, text), from, to, status, true);
    }

    public long prepareMine(CurrentUser user, WorkLogStatus status, LocalDate from, LocalDate to, String text) {
        return prepare(specMine(user, status, from, to, text), from, to, status, false);
    }

    private long prepare(Specification<DailyWorkLog> spec, LocalDate from, LocalDate to, WorkLogStatus status,
                         boolean teamExport) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BadRequestException("The 'to' date cannot be before the 'from' date");
        }
        if (teamExport && status == WorkLogStatus.DRAFT) {
            throw new BadRequestException("Drafts are private to their author and are not part of the team export");
        }
        long count = repository.count(spec);
        if (count > maxRows) {
            throw new BadRequestException("This export would contain " + count + " rows, more than the limit of "
                    + maxRows + ". Narrow the date range or add a filter.");
        }
        return count;
    }

    /** Writes the team export. Call {@link #prepareAll} first (it also checks the caller is an administrator). */
    public void writeAll(CurrentUser actor, Format format, Long employeeId, String department, WorkLogStatus status,
                         LocalDate from, LocalDate to, String text, OutputStream out) throws IOException {
        if (!actor.isAdmin()) {
            throw new ForbiddenOperationException("Only administrators can export all work reports");
        }
        long rows = write(format, specAll(employeeId, department, status, from, to, text), out);
        auditService.recordIndependent(AuditActions.WORKLOG_EXPORTED, "DailyWorkLog", null, AuditOutcome.SUCCESS,
                "team export as " + format + ", " + rows + " rows, filters: " + describe(employeeId, department, status, from, to, text));
    }

    public void writeMine(CurrentUser user, Format format, WorkLogStatus status, LocalDate from, LocalDate to,
                          String text, OutputStream out) throws IOException {
        long rows = write(format, specMine(user, status, from, to, text), out);
        auditService.recordIndependent(AuditActions.WORKLOG_EXPORTED, "DailyWorkLog", user.id(), AuditOutcome.SUCCESS,
                "own export as " + format + ", " + rows + " rows");
    }

    private Specification<DailyWorkLog> specAll(Long employeeId, String department, WorkLogStatus status,
                                                LocalDate from, LocalDate to, String text) {
        return WorkLogSpecifications.filter(employeeId, status, from, to, text, false)
                .and(WorkLogSpecifications.inDepartment(department));
    }

    private Specification<DailyWorkLog> specMine(CurrentUser user, WorkLogStatus status, LocalDate from, LocalDate to,
                                                 String text) {
        return WorkLogSpecifications.filter(user.id(), status, from, to, text, true);
    }

    private long write(Format format, Specification<DailyWorkLog> spec, OutputStream out) throws IOException {
        long written = 0;
        if (format == Format.CSV) {
            try (CsvWriter csv = new CsvWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
                csv.writeRow(HEADER);
                for (int page = 0; ; page++) {
                    Page<DailyWorkLog> chunk = repository.findAll(spec, PageRequest.of(page, PAGE_SIZE, ORDER));
                    for (DailyWorkLog log : chunk.getContent()) {
                        csv.writeRow(row(log));
                        written++;
                    }
                    if (!chunk.hasNext()) {
                        break;
                    }
                }
            }
        } else {
            try (XlsxWriter xlsx = new XlsxWriter(out, "Work reports", HEADER, WIDTHS)) {
                for (int page = 0; ; page++) {
                    Page<DailyWorkLog> chunk = repository.findAll(spec, PageRequest.of(page, PAGE_SIZE, ORDER));
                    for (DailyWorkLog log : chunk.getContent()) {
                        xlsx.writeRow(row(log));
                        written++;
                    }
                    if (!chunk.hasNext()) {
                        break;
                    }
                }
            }
        }
        return written;
    }

    /** One export row. Typed values (number, date, instant) stay typed; text is neutralised by the writers. */
    static List<Object> row(DailyWorkLog log) {
        User employee = log.getEmployee();
        return java.util.Arrays.asList(employee.getId(), employee.getFullName(), employee.getDepartment(), log.getWorkDate(),
                log.getTaskTitle(), log.getDescription(), log.getHoursSpent(), label(log.getStatus()),
                log.getCreatedAt(), log.getUpdatedAt());
    }

    private static String label(WorkLogStatus status) {
        return switch (status) {
            case DRAFT -> "Draft";
            case IN_PROGRESS -> "In progress";
            case COMPLETED -> "Completed";
            case BLOCKED -> "Blocked";
        };
    }

    private static String describe(Long employeeId, String department, WorkLogStatus status, LocalDate from,
                                   LocalDate to, String text) {
        StringBuilder sb = new StringBuilder();
        if (employeeId != null) {
            sb.append("employee ").append(employeeId).append(' ');
        }
        if (department != null && !department.isBlank()) {
            sb.append("department filter ");
        }
        if (status != null) {
            sb.append("status ").append(status).append(' ');
        }
        if (from != null || to != null) {
            sb.append("dates ").append(from).append("..").append(to).append(' ');
        }
        if (text != null && !text.isBlank()) {
            sb.append("text filter");
        }
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    /** "worktrack-work-reports-2026-10-21.csv": report type and export date. */
    public String filename(boolean mine, Format format) {
        return "worktrack-" + (mine ? "my-work-reports-" : "work-reports-") + LocalDate.now(clock)
                + (format == Format.CSV ? ".csv" : ".xlsx");
    }
}
