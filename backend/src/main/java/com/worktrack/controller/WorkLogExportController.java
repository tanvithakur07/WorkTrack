package com.worktrack.controller;

import com.worktrack.entity.WorkLogStatus;
import com.worktrack.exception.BadRequestException;
import com.worktrack.security.WorkTrackUserDetails;
import com.worktrack.service.WorkLogExportService;
import com.worktrack.service.WorkLogExportService.Format;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Downloads of work reports. Both are GET requests with no side effect on the reports, so they need no CSRF
 * token; they are authenticated by the session like every other call. Everything is validated BEFORE the
 * first byte is written, so a bad request still gets a normal JSON error.
 */
@RestController
@RequestMapping("/api/work-logs")
public class WorkLogExportController {

    private static final String CSV = "text/csv; charset=UTF-8";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final WorkLogExportService exportService;

    public WorkLogExportController(WorkLogExportService exportService) {
        this.exportService = exportService;
    }

    /** Administrators: all submitted reports (never drafts), filtered. */
    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public void exportAll(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) WorkLogStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @AuthenticationPrincipal WorkTrackUserDetails principal,
            HttpServletResponse response) throws IOException {
        Format chosen = parse(format);
        exportService.prepareAll(principal.toCurrentUser(), employeeId, department, status, from, to, q);
        startDownload(response, chosen, exportService.filename(false, chosen));
        exportService.writeAll(principal.toCurrentUser(), chosen, employeeId, department, status, from, to, q,
                response.getOutputStream());
    }

    /** Anyone signed in: their own reports only. */
    @GetMapping("/my/export")
    @PreAuthorize("isAuthenticated()")
    public void exportMine(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) WorkLogStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @AuthenticationPrincipal WorkTrackUserDetails principal,
            HttpServletResponse response) throws IOException {
        Format chosen = parse(format);
        exportService.prepareMine(principal.toCurrentUser(), status, from, to, q);
        startDownload(response, chosen, exportService.filename(true, chosen));
        exportService.writeMine(principal.toCurrentUser(), chosen, status, from, to, q, response.getOutputStream());
    }

    private static Format parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "csv" -> Format.CSV;
            case "xlsx" -> Format.XLSX;
            default -> throw new BadRequestException("format must be 'csv' or 'xlsx'");
        };
    }

    private static void startDownload(HttpServletResponse response, Format format, String filename) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(format == Format.CSV ? CSV : XLSX);
        // the filename is built by the server from fixed text and a date: it is plain ASCII with no quotes
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
