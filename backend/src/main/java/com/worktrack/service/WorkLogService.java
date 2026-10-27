package com.worktrack.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.worktrack.dto.MissingSubmissionResponse;
import com.worktrack.dto.PageResponse;
import com.worktrack.dto.TeamWorkSummaryResponse;
import com.worktrack.dto.WorkLogRequest;
import com.worktrack.dto.WorkLogResponse;
import com.worktrack.dto.WorkLogSummaryResponse;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.entity.DailyWorkLog;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveStatus;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;
import com.worktrack.entity.WorkLogStatus;
import com.worktrack.exception.BadRequestException;
import com.worktrack.exception.ConflictException;
import com.worktrack.exception.ForbiddenOperationException;
import com.worktrack.exception.ResourceNotFoundException;
import com.worktrack.repository.DailyWorkLogRepository;
import com.worktrack.repository.LeaveRequestRepository;
import com.worktrack.repository.UserRepository;
import com.worktrack.repository.WorkLogSpecifications;
import com.worktrack.security.CurrentUser;

/**
 * All daily-work-report rules live here, not in controllers.
 *
 * Who can do what (WorkTrack has two roles, so no manager role was invented):
 * - Every signed-in person writes, edits and deletes only their OWN entries. The owner is always the
 *   authenticated user; a request body has no employee id.
 * - Administrators can read every entry, the team summary and the missing-report list. They cannot
 *   edit or delete other people's entries.
 *
 * Rules (also in the README):
 * - The work date may not be in the future and at most 30 days in the past (checked on create and
 *   whenever the date of an entry is changed).
 * - 0.25 to 24 hours per entry, and at most 24 hours per person per day across all entries.
 * - DRAFT entries are private to the author (administrators do not see them in the team views), can be deleted, and do not count as hours or as a submitted
 *   day. Entries with any other status count. Only drafts can be deleted.
 * - A day is "missing" for an employee when it is a weekday between their joining date and yesterday, they
 *   are not on APPROVED leave, and they have no submitted entry. Public holidays are not modelled.
 *
 * Concurrency: create and update lock the author's row (SELECT ... FOR UPDATE) at READ_COMMITTED, exactly
 * like leave submission, so two simultaneous entries cannot both pass the 24-hour check.
 */
@Service
public class WorkLogService {

    static final int MAX_BACKDATE_DAYS = 30;
    static final BigDecimal MAX_HOURS_PER_DAY = new BigDecimal("24");
    static final int MAX_SUMMARY_RANGE_DAYS = 366;
    static final int MAX_MISSING_RANGE_DAYS = 62;
    static final int DEFAULT_RANGE_DAYS = 7;

    private static final Long NO_EXCLUDED_ID = -1L;
    private static final Long ALL_EMPLOYEES = -1L;

    private final DailyWorkLogRepository workLogRepository;
    private final UserRepository userRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final Clock clock;
    private final AuditService auditService;

    public WorkLogService(DailyWorkLogRepository workLogRepository,
                          UserRepository userRepository,
                          LeaveRequestRepository leaveRequestRepository,
                          Clock clock,
                          AuditService auditService) {
        this.workLogRepository = workLogRepository;
        this.userRepository = userRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.clock = clock;
        this.auditService = auditService;
    }

    /* ------------------------------------------------------------------ write */

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkLogResponse create(CurrentUser currentUser, WorkLogRequest request) {
        User author = lockActiveAuthor(currentUser);
        validateDate(request.workDate());
        BigDecimal hours = normalizeHours(request.hoursSpent());
        assertDayTotal(author.getId(), request.workDate(), hours, NO_EXCLUDED_ID);

        DailyWorkLog log = new DailyWorkLog(author, request.workDate(), request.taskTitle().trim(),
                request.description().trim(), hours, request.status());
        DailyWorkLog saved = workLogRepository.saveAndFlush(log);
        auditService.record(AuditActions.WORKLOG_CREATED, "DailyWorkLog", saved.getId(), AuditOutcome.SUCCESS,
                "date " + saved.getWorkDate() + ", " + hours + " h, status " + saved.getStatus());
        return WorkLogResponse.from(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkLogResponse update(Long id, CurrentUser currentUser, WorkLogRequest request) {
        User author = lockActiveAuthor(currentUser);
        DailyWorkLog log = find(id);
        requireOwner(log, currentUser, "You can only edit your own work reports");

        if (!request.workDate().equals(log.getWorkDate())) {
            validateDate(request.workDate());
        }
        BigDecimal hours = normalizeHours(request.hoursSpent());
        assertDayTotal(author.getId(), request.workDate(), hours, log.getId());

        String before = "date " + log.getWorkDate() + ", " + log.getHoursSpent() + " h, status " + log.getStatus();
        log.update(request.workDate(), request.taskTitle().trim(), request.description().trim(),
                hours, request.status());
        DailyWorkLog saved = workLogRepository.saveAndFlush(log);
        auditService.record(AuditActions.WORKLOG_UPDATED, "DailyWorkLog", saved.getId(), AuditOutcome.SUCCESS,
                "before: " + before + "; after: date " + saved.getWorkDate() + ", " + hours + " h, status " + saved.getStatus());
        return WorkLogResponse.from(saved);
    }

    @Transactional
    public void delete(Long id, CurrentUser currentUser) {
        DailyWorkLog log = find(id);
        requireOwner(log, currentUser, "You can only delete your own work reports");
        if (log.getStatus() != WorkLogStatus.DRAFT) {
            throw new ConflictException("Only drafts can be deleted. Change the status instead, or edit the entry");
        }
        workLogRepository.delete(log);
        workLogRepository.flush();
        auditService.record(AuditActions.WORKLOG_DELETED, "DailyWorkLog", id, AuditOutcome.SUCCESS,
                "draft for " + log.getWorkDate() + " deleted by its author");
    }

    /* ------------------------------------------------------------------- read */

    /**
     * Used for "my entries" (ownerId = caller, drafts included) and the admin team list (ownerId optional,
     * includeDrafts = false because drafts are private to their author).
     */
    @Transactional(readOnly = true)
    public PageResponse<WorkLogResponse> search(Long ownerId, WorkLogStatus status, LocalDate from, LocalDate to,
                                                String text, boolean includeDrafts, Pageable pageable) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BadRequestException("The 'to' date cannot be before the 'from' date");
        }
        Page<WorkLogResponse> page = workLogRepository
                .findAll(WorkLogSpecifications.filter(ownerId, status, from, to, text, includeDrafts), pageable)
                .map(WorkLogResponse::from);
        return PageResponse.from(page);
    }

    @Transactional(readOnly = true)
    public WorkLogResponse getById(Long id, CurrentUser currentUser) {
        DailyWorkLog log = find(id);
        boolean owner = log.getEmployee().getId().equals(currentUser.id());
        if (!owner && !currentUser.isAdmin()) {
            throw new ForbiddenOperationException("You can only view your own work reports");
        }
        if (!owner && log.getStatus() == WorkLogStatus.DRAFT) {
            throw new ForbiddenOperationException("This entry is a draft and is private to its author");
        }
        return WorkLogResponse.from(log);
    }

    @Transactional(readOnly = true)
    public WorkLogSummaryResponse mySummary(CurrentUser currentUser, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        checkRange(start, end, MAX_SUMMARY_RANGE_DAYS);

        List<WorkLogSummaryResponse.DayTotal> days = workLogRepository
                .submittedDayTotals(currentUser.id(), start, end, WorkLogStatus.DRAFT).stream()
                .map(row -> new WorkLogSummaryResponse.DayTotal(row.getWorkDate(), row.getHours(), row.getEntryCount()))
                .toList();
        BigDecimal total = days.stream().map(WorkLogSummaryResponse.DayTotal::hours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long entries = days.stream().mapToLong(WorkLogSummaryResponse.DayTotal::entries).sum();

        Map<String, Long> statusCounts = zeroFilledStatusCounts(true);
        workLogRepository.statusCountsForEmployee(currentUser.id(), start, end)
                .forEach(row -> statusCounts.put(row.getStatus().name(), row.getTotal()));

        return new WorkLogSummaryResponse(start, end, total, entries, days.size(),
                statusCounts.get(WorkLogStatus.DRAFT.name()), statusCounts, days);
    }

    @Transactional(readOnly = true)
    public TeamWorkSummaryResponse teamSummary(LocalDate from, LocalDate to, Long employeeId) {
        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        checkRange(start, end, MAX_SUMMARY_RANGE_DAYS);
        Long filter = employeeId == null ? ALL_EMPLOYEES : employeeId;

        List<TeamWorkSummaryResponse.EmployeeTotal> employees = workLogRepository
                .submittedTotalsByEmployee(filter, start, end, WorkLogStatus.DRAFT).stream()
                .map(row -> new TeamWorkSummaryResponse.EmployeeTotal(row.getEmployeeId(), row.getEmployeeName(),
                        row.getDepartment(), row.getHours(), row.getEntryCount()))
                .toList();
        BigDecimal total = employees.stream().map(TeamWorkSummaryResponse.EmployeeTotal::hours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long entries = employees.stream().mapToLong(TeamWorkSummaryResponse.EmployeeTotal::entries).sum();

        Map<String, Long> statusCounts = zeroFilledStatusCounts(false);
        workLogRepository.submittedStatusCounts(filter, start, end, WorkLogStatus.DRAFT)
                .forEach(row -> statusCounts.put(row.getStatus().name(), row.getTotal()));

        return new TeamWorkSummaryResponse(start, end, total, entries, employees.size(),
                userRepository.countByStatusAndRole(EmploymentStatus.ACTIVE, Role.EMPLOYEE),
                statusCounts, employees);
    }

    /**
     * Active employees with working days that have no submitted report. Defaults to the last 7 days
     * ending yesterday (today is still open). Days on APPROVED leave, weekends and days before the joining
     * date are never reported as missing.
     */
    @Transactional(readOnly = true)
    public List<MissingSubmissionResponse> missingSubmissions(LocalDate from, LocalDate to, Long employeeId) {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        LocalDate end = to != null ? to : yesterday;
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        checkRange(start, end, MAX_MISSING_RANGE_DAYS);
        if (end.isAfter(yesterday)) {
            end = yesterday;
        }
        if (start.isAfter(end)) {
            return List.of();
        }

        Map<Long, Set<LocalDate>> submitted = new HashMap<>();
        workLogRepository.submittedDays(start, end, WorkLogStatus.DRAFT)
                .forEach(row -> submitted.computeIfAbsent(row.getEmployeeId(), k -> new HashSet<>()).add(row.getWorkDate()));
        Map<Long, List<LeaveRequest>> leaves = new HashMap<>();
        for (LeaveRequest leave : leaveRequestRepository.findTouching(LeaveStatus.APPROVED, start, end)) {
            leaves.computeIfAbsent(leave.getEmployee().getId(), k -> new ArrayList<>()).add(leave);
        }

        List<MissingSubmissionResponse> result = new ArrayList<>();
        for (User employee : userRepository.findByStatusAndRoleOrderByFullNameAsc(EmploymentStatus.ACTIVE, Role.EMPLOYEE)) {
            if (employeeId != null && !employeeId.equals(employee.getId())) {
                continue;
            }
            Set<LocalDate> done = submitted.getOrDefault(employee.getId(), Set.of());
            List<LeaveRequest> away = leaves.getOrDefault(employee.getId(), List.of());
            LocalDate first = employee.getJoiningDate().isAfter(start) ? employee.getJoiningDate() : start;
            List<LocalDate> missing = new ArrayList<>();
            for (LocalDate day = first; !day.isAfter(end); day = day.plusDays(1)) {
                if (isWeekend(day) || done.contains(day) || onLeave(away, day)) {
                    continue;
                }
                missing.add(day);
            }
            if (!missing.isEmpty()) {
                result.add(new MissingSubmissionResponse(employee.getId(), employee.getFullName(),
                        employee.getEmail(), employee.getDepartment(), missing.size(), missing));
            }
        }
        return result;
    }

    /* ---------------------------------------------------------------- helpers */

    private User lockActiveAuthor(CurrentUser currentUser) {
        User author = userRepository.findByIdForUpdate(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));
        if (author.getStatus() != EmploymentStatus.ACTIVE) {
            throw new ForbiddenOperationException("Inactive accounts cannot write work reports");
        }
        return author;
    }

    private DailyWorkLog find(Long id) {
        return workLogRepository.findDetailedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Work report not found"));
    }

    private static void requireOwner(DailyWorkLog log, CurrentUser currentUser, String message) {
        if (!log.getEmployee().getId().equals(currentUser.id())) {
            throw new ForbiddenOperationException(message);
        }
    }

    private void validateDate(LocalDate workDate) {
        LocalDate today = LocalDate.now(clock);
        if (workDate.isAfter(today)) {
            throw new BadRequestException("The work date cannot be in the future");
        }
        if (workDate.isBefore(today.minusDays(MAX_BACKDATE_DAYS))) {
            throw new BadRequestException("Work reports can be added for the last " + MAX_BACKDATE_DAYS + " days only");
        }
    }

    private static BigDecimal normalizeHours(BigDecimal hours) {
        return hours.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private void assertDayTotal(Long employeeId, LocalDate date, BigDecimal hours, Long excludedId) {
        BigDecimal already = workLogRepository.sumHoursOnDay(employeeId, date, excludedId);
        BigDecimal total = (already == null ? BigDecimal.ZERO : already).add(hours);
        if (total.compareTo(MAX_HOURS_PER_DAY) > 0) {
            throw new BadRequestException("This would make " + total.stripTrailingZeros().toPlainString()
                    + " hours on " + date + ". A day can hold at most 24 hours across all entries");
        }
    }

    private static void checkRange(LocalDate from, LocalDate to, int maxDays) {
        if (to.isBefore(from)) {
            throw new BadRequestException("The 'to' date cannot be before the 'from' date");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > maxDays) {
            throw new BadRequestException("Please choose a range of at most " + maxDays + " days");
        }
    }

    private static Map<String, Long> zeroFilledStatusCounts(boolean includeDraft) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (WorkLogStatus status : WorkLogStatus.values()) {
            if (includeDraft || status != WorkLogStatus.DRAFT) {
                counts.put(status.name(), 0L);
            }
        }
        return counts;
    }

    private static boolean isWeekend(LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    private static boolean onLeave(List<LeaveRequest> leaves, LocalDate day) {
        for (LeaveRequest leave : leaves) {
            if (!day.isBefore(leave.getStartDate()) && !day.isAfter(leave.getEndDate())) {
                return true;
            }
        }
        return false;
    }
}
