package com.worktrack.service;

import com.worktrack.dto.AdminAnalyticsResponse;
import com.worktrack.dto.EmployeeAnalyticsResponse;
import com.worktrack.dto.LeaveResponse;
import com.worktrack.dto.WorkLogResponse;
import com.worktrack.dto.WorkLogSummaryResponse;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveStatus;
import com.worktrack.entity.Role;
import com.worktrack.entity.WorkLogStatus;
import com.worktrack.exception.BadRequestException;
import com.worktrack.repository.DailyWorkLogRepository;
import com.worktrack.repository.LeaveRequestRepository;
import com.worktrack.repository.UserRepository;
import com.worktrack.repository.WorkLogSpecifications;
import com.worktrack.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The numbers behind the dashboard charts. Every figure is a database aggregate (GROUP BY queries) or a
 * count; no table is loaded into memory to be summed. All endpoints apply the same date rules: a period
 * defaults to the last 30 days ending today, may not run backwards and covers at most 366 days.
 *
 * "Hours" are always the hours employees REPORTED. They are not verified measurements of productivity.
 */
@Service
public class AnalyticsService {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 366;
    private static final String[] STATUS_ORDER = {"DRAFT", "IN_PROGRESS", "COMPLETED", "BLOCKED"};

    private final DailyWorkLogRepository workLogRepository;
    private final UserRepository userRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final Clock clock;

    public AnalyticsService(DailyWorkLogRepository workLogRepository, UserRepository userRepository,
                            LeaveRequestRepository leaveRequestRepository, Clock clock) {
        this.workLogRepository = workLogRepository;
        this.userRepository = userRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.clock = clock;
    }

    /** The signed-in employee's own picture. The employee id is the caller's; nothing else can be asked for. */
    @Transactional(readOnly = true)
    public EmployeeAnalyticsResponse employee(CurrentUser caller, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
        checkRange(start, end);

        List<WorkLogSummaryResponse.DayTotal> days = workLogRepository
                .submittedDayTotals(caller.id(), start, end, WorkLogStatus.DRAFT).stream()
                .map(row -> new WorkLogSummaryResponse.DayTotal(row.getWorkDate(), row.getHours(), row.getEntryCount()))
                .toList();
        BigDecimal hours = days.stream().map(WorkLogSummaryResponse.DayTotal::hours).reduce(BigDecimal.ZERO, BigDecimal::add);
        long entries = days.stream().mapToLong(WorkLogSummaryResponse.DayTotal::entries).sum();

        Map<String, Long> byStatus = zeroFilled();
        workLogRepository.statusCountsForEmployee(caller.id(), start, end)
                .forEach(row -> byStatus.put(row.getStatus().name(), row.getTotal()));

        List<WorkLogResponse> recent = workLogRepository.findAll(
                        WorkLogSpecifications.filter(caller.id(), null, null, null, null, true),
                        PageRequest.of(0, 5, Sort.by(Sort.Order.desc("workDate"), Sort.Order.desc("id"))))
                .map(WorkLogResponse::from).getContent();

        return new EmployeeAnalyticsResponse(start, end, hours, entries, days.size(),
                byStatus.get("COMPLETED"), byStatus.get("IN_PROGRESS"), byStatus, days, recent);
    }

    /** Administrators only (URL rule + @PreAuthorize in the controller). {@code day} defaults to today. */
    @Transactional(readOnly = true)
    public AdminAnalyticsResponse admin(LocalDate from, LocalDate to, LocalDate day) {
        LocalDate today = LocalDate.now(clock);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
        checkRange(start, end);
        LocalDate selected = day != null ? day : today;

        long expected = userRepository.countByStatusAndRole(EmploymentStatus.ACTIVE, Role.EMPLOYEE);

        Map<LocalDate, DailyWorkLogRepository.DayRow> hoursByDay = new HashMap<>();
        workLogRepository.submittedTotalsByDay(start, end, WorkLogStatus.DRAFT)
                .forEach(row -> hoursByDay.put(row.getWorkDate(), row));
        Map<LocalDate, Long> reportersByDay = new HashMap<>();
        workLogRepository.reportersByDay(start, end, WorkLogStatus.DRAFT, Role.EMPLOYEE, EmploymentStatus.ACTIVE)
                .forEach(row -> reportersByDay.put(row.getWorkDate(), row.getReporterCount()));

        List<AdminAnalyticsResponse.TrendPoint> trend = new ArrayList<>();
        BigDecimal totalHours = BigDecimal.ZERO;
        long totalEntries = 0;
        double coverageSum = 0;
        int coverageDays = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            DailyWorkLogRepository.DayRow row = hoursByDay.get(d);
            BigDecimal hours = row == null ? BigDecimal.ZERO : row.getHours();
            long entries = row == null ? 0 : row.getEntryCount();
            long reporters = reportersByDay.getOrDefault(d, 0L);
            boolean weekend = isWeekend(d);
            Double coverage = coverage(d, today, weekend, reporters, expected);
            if (coverage != null && d.isBefore(today)) { // today is still in progress: left out of the average
                coverageSum += coverage;
                coverageDays++;
            }
            totalHours = totalHours.add(hours);
            totalEntries += entries;
            trend.add(new AdminAnalyticsResponse.TrendPoint(d, hours, entries, reporters, coverage, weekend));
        }

        Map<String, Long> byStatus = zeroFilled();
        byStatus.remove("DRAFT"); // drafts are private to their author: not part of any team figure
        workLogRepository.submittedStatusCounts(-1L, start, end, WorkLogStatus.DRAFT)
                .forEach(row -> byStatus.put(row.getStatus().name(), row.getTotal()));

        AdminAnalyticsResponse.DaySnapshot snapshot = daySnapshot(selected, today, expected);

        List<WorkLogResponse> recentReports = workLogRepository.findAll(
                        WorkLogSpecifications.filter(null, null, null, null, null, false),
                        PageRequest.of(0, 5, Sort.by(Sort.Order.desc("workDate"), Sort.Order.desc("id"))))
                .map(WorkLogResponse::from).getContent();
        List<LeaveResponse> recentLeaves = leaveRequestRepository.findTop8ByOrderByUpdatedAtDesc()
                .stream().map(LeaveResponse::from).toList();

        return new AdminAnalyticsResponse(start, end,
                userRepository.countByStatus(EmploymentStatus.ACTIVE), expected,
                leaveRequestRepository.countByStatus(LeaveStatus.PENDING),
                totalHours, totalEntries, byStatus, snapshot,
                coverageDays == 0 ? null : round1(coverageSum / coverageDays),
                trend, recentReports, recentLeaves);
    }

    private AdminAnalyticsResponse.DaySnapshot daySnapshot(LocalDate day, LocalDate today, long expected) {
        long entries = 0;
        BigDecimal hours = BigDecimal.ZERO;
        for (DailyWorkLogRepository.DayRow row : workLogRepository.submittedTotalsByDay(day, day, WorkLogStatus.DRAFT)) {
            entries += row.getEntryCount();
            hours = hours.add(row.getHours());
        }
        long reporters = 0;
        for (DailyWorkLogRepository.ReporterRow row : workLogRepository
                .reportersByDay(day, day, WorkLogStatus.DRAFT, Role.EMPLOYEE, EmploymentStatus.ACTIVE)) {
            reporters += row.getReporterCount();
        }
        boolean weekend = isWeekend(day);
        return new AdminAnalyticsResponse.DaySnapshot(day, entries, reporters, expected, hours,
                coverage(day, today, weekend, reporters, expected), weekend);
    }

    /** null when it makes no sense: weekend, a day that has not happened yet, or nobody is expected to report. */
    private static Double coverage(LocalDate day, LocalDate today, boolean weekend, long reporters, long expected) {
        if (weekend || day.isAfter(today) || expected == 0) {
            return null;
        }
        return round1(Math.min(100.0, reporters * 100.0 / expected));
    }

    private static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean isWeekend(LocalDate day) {
        return day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static Map<String, Long> zeroFilled() {
        Map<String, Long> map = new LinkedHashMap<>();
        for (String status : STATUS_ORDER) {
            map.put(status, 0L);
        }
        return map;
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BadRequestException("The 'to' date cannot be before the 'from' date");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new BadRequestException("Choose a period of at most " + MAX_DAYS + " days");
        }
    }
}
