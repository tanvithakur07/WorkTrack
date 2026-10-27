package com.worktrack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.worktrack.dto.MissingSubmissionResponse;
import com.worktrack.dto.WorkLogRequest;
import com.worktrack.dto.WorkLogResponse;
import com.worktrack.dto.WorkLogSummaryResponse;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.entity.DailyWorkLog;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveStatus;
import com.worktrack.entity.LeaveType;
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
import com.worktrack.security.CurrentUser;

/** Business rules of the daily work tracker with a fixed clock: "today" is Wednesday 4 March 2026. */
@ExtendWith(MockitoExtension.class)
class WorkLogServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-04T09:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 4);

    @Mock
    private DailyWorkLogRepository workLogRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private LeaveRequestRepository leaveRepository;
    @Mock
    private AuditService auditService;

    private WorkLogService service;
    private User priya;
    private User kenji;
    private User ada;
    private CurrentUser priyaCaller;
    private CurrentUser kenjiCaller;
    private CurrentUser adaCaller;

    @BeforeEach
    void setUp() {
        service = new WorkLogService(workLogRepository, userRepository, leaveRepository, CLOCK, auditService);
        priya = user(1L, "Priya Nair", Role.EMPLOYEE, LocalDate.of(2024, 1, 15));
        kenji = user(2L, "Kenji Watanabe", Role.EMPLOYEE, LocalDate.of(2024, 1, 15));
        ada = user(3L, "Ada Admin", Role.ADMIN, LocalDate.of(2024, 1, 15));
        priyaCaller = new CurrentUser(1L, Role.EMPLOYEE);
        kenjiCaller = new CurrentUser(2L, Role.EMPLOYEE);
        adaCaller = new CurrentUser(3L, Role.ADMIN);
        lenient().when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(priya));
        lenient().when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(kenji));
        lenient().when(userRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(ada));
        lenient().when(workLogRepository.saveAndFlush(any(DailyWorkLog.class))).thenAnswer(call -> call.getArgument(0));
    }

    /* ------------------------------ creating ------------------------------ */

    @Test
    @DisplayName("the entry belongs to the signed-in user, text is trimmed and hours are stored with 2 decimals")
    void createStoresEntryForCaller() {
        WorkLogResponse response = service.create(priyaCaller,
                request(TODAY, "  Fix login bug  ", "  Found the cause.  ", "7.5", WorkLogStatus.COMPLETED));

        ArgumentCaptor<DailyWorkLog> saved = ArgumentCaptor.forClass(DailyWorkLog.class);
        verify(workLogRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmployee()).isSameAs(priya);
        assertThat(saved.getValue().getTaskTitle()).isEqualTo("Fix login bug");
        assertThat(saved.getValue().getDescription()).isEqualTo("Found the cause.");
        assertThat(saved.getValue().getHoursSpent()).isEqualByComparingTo("7.5").hasScaleOf(2);
        assertThat(response.employeeName()).isEqualTo("Priya Nair");
        assertThat(response.status()).isEqualTo(WorkLogStatus.COMPLETED);
    }

    @Test
    @DisplayName("the author's row is locked before the day total is read (two simultaneous entries cannot both pass)")
    void createLocksBeforeCheckingTheDayTotal() {
        service.create(priyaCaller, request(TODAY, "Task one", "Did a thing", "2", WorkLogStatus.IN_PROGRESS));

        InOrder order = inOrder(userRepository, workLogRepository);
        order.verify(userRepository).findByIdForUpdate(1L);
        order.verify(workLogRepository).sumHoursOnDay(eq(1L), eq(TODAY), any());
        order.verify(workLogRepository).saveAndFlush(any(DailyWorkLog.class));
    }

    @Test
    @DisplayName("a future work date is rejected")
    void rejectsFutureDate() {
        assertThatThrownBy(() -> service.create(priyaCaller,
                request(TODAY.plusDays(1), "Task one", "Did a thing", "2", WorkLogStatus.COMPLETED)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("future");
        verify(workLogRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("work can be added for today and up to 30 days back, not for 31")
    void backdateWindow() {
        service.create(priyaCaller, request(TODAY.minusDays(30), "Old task", "Late entry", "1", WorkLogStatus.COMPLETED));
        assertThatThrownBy(() -> service.create(priyaCaller,
                request(TODAY.minusDays(31), "Older task", "Too late", "1", WorkLogStatus.COMPLETED)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("30 days");
    }

    @Test
    @DisplayName("a day can hold exactly 24 hours across entries, but not more")
    void dayTotalCap() {
        when(workLogRepository.sumHoursOnDay(eq(1L), eq(TODAY), any())).thenReturn(new BigDecimal("20.00"));

        service.create(priyaCaller, request(TODAY, "Late shift", "Four more hours", "4", WorkLogStatus.COMPLETED));
        assertThatThrownBy(() -> service.create(priyaCaller,
                request(TODAY, "One more", "Over the limit", "4.25", WorkLogStatus.COMPLETED)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("24 hours");
    }

    @Test
    @DisplayName("an inactive account cannot write reports")
    void inactiveAccountCannotCreate() {
        priya.setStatus(EmploymentStatus.INACTIVE);

        assertThatThrownBy(() -> service.create(priyaCaller,
                request(TODAY, "Task one", "Did a thing", "1", WorkLogStatus.COMPLETED)))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(workLogRepository, never()).saveAndFlush(any());
    }

    /* ------------------------------ updating ------------------------------ */

    @Test
    @DisplayName("the owner can edit an entry; the day total ignores the entry being edited")
    void ownerCanUpdate() {
        DailyWorkLog log = log(10L, priya, TODAY, WorkLogStatus.IN_PROGRESS, "3");
        when(workLogRepository.findDetailedById(10L)).thenReturn(Optional.of(log));

        WorkLogResponse response = service.update(10L, priyaCaller,
                request(TODAY, "Renamed task", "New description", "5", WorkLogStatus.COMPLETED));

        verify(workLogRepository).sumHoursOnDay(1L, TODAY, 10L);
        assertThat(response.taskTitle()).isEqualTo("Renamed task");
        assertThat(log.getHoursSpent()).isEqualByComparingTo("5");
        assertThat(log.getStatus()).isEqualTo(WorkLogStatus.COMPLETED);
        assertThat(log.getEmployee()).isSameAs(priya);
    }

    @Test
    @DisplayName("nobody else can edit an entry, administrators included")
    void othersCannotUpdate() {
        DailyWorkLog log = log(10L, priya, TODAY, WorkLogStatus.IN_PROGRESS, "3");
        when(workLogRepository.findDetailedById(10L)).thenReturn(Optional.of(log));

        for (CurrentUser caller : List.of(kenjiCaller, adaCaller)) {
            assertThatThrownBy(() -> service.update(10L, caller,
                    request(TODAY, "Hijacked", "Not yours", "1", WorkLogStatus.COMPLETED)))
                    .isInstanceOf(ForbiddenOperationException.class);
        }
        assertThat(log.getTaskTitle()).isEqualTo("Existing task");
        verify(workLogRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("an old entry can be edited without changing its date, but cannot be moved outside the 30-day window")
    void oldEntryKeepsItsDate() {
        LocalDate old = TODAY.minusDays(45);
        DailyWorkLog log = log(10L, priya, old, WorkLogStatus.IN_PROGRESS, "3");
        when(workLogRepository.findDetailedById(10L)).thenReturn(Optional.of(log));

        service.update(10L, priyaCaller, request(old, "Existing task", "Edited text", "3", WorkLogStatus.COMPLETED));
        assertThatThrownBy(() -> service.update(10L, priyaCaller,
                request(old.minusDays(1), "Existing task", "Edited text", "3", WorkLogStatus.COMPLETED)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("editing an unknown entry is a 404")
    void updateUnknown() {
        when(workLogRepository.findDetailedById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, priyaCaller,
                request(TODAY, "Task one", "Did a thing", "1", WorkLogStatus.COMPLETED)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /* ------------------------------ deleting ------------------------------ */

    @Test
    @DisplayName("the owner can delete a draft")
    void ownerDeletesDraft() {
        DailyWorkLog draft = log(10L, priya, TODAY, WorkLogStatus.DRAFT, "1");
        when(workLogRepository.findDetailedById(10L)).thenReturn(Optional.of(draft));

        service.delete(10L, priyaCaller);

        verify(workLogRepository).delete(draft);
    }

    @Test
    @DisplayName("only drafts can be deleted: a submitted entry gives 409 and stays")
    void submittedEntryCannotBeDeleted() {
        DailyWorkLog done = log(10L, priya, TODAY, WorkLogStatus.COMPLETED, "1");
        when(workLogRepository.findDetailedById(10L)).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> service.delete(10L, priyaCaller)).isInstanceOf(ConflictException.class);
        verify(workLogRepository, never()).delete(any(DailyWorkLog.class));
    }

    @Test
    @DisplayName("nobody else can delete someone's draft, administrators included")
    void othersCannotDelete() {
        DailyWorkLog draft = log(10L, priya, TODAY, WorkLogStatus.DRAFT, "1");
        when(workLogRepository.findDetailedById(10L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.delete(10L, kenjiCaller)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.delete(10L, adaCaller)).isInstanceOf(ForbiddenOperationException.class);
        verify(workLogRepository, never()).delete(any(DailyWorkLog.class));
    }

    /* ------------------------------- reading ------------------------------- */

    @Test
    @DisplayName("owner and administrator can read a submitted entry; another employee cannot")
    void readAccess() {
        DailyWorkLog log = log(10L, priya, TODAY, WorkLogStatus.COMPLETED, "2");
        when(workLogRepository.findDetailedById(10L)).thenReturn(Optional.of(log));

        assertThat(service.getById(10L, priyaCaller).id()).isEqualTo(10L);
        assertThat(service.getById(10L, adaCaller).id()).isEqualTo(10L);
        assertThatThrownBy(() -> service.getById(10L, kenjiCaller)).isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    @DisplayName("a draft is private: even an administrator cannot read someone else's draft")
    void draftsArePrivate() {
        DailyWorkLog draft = log(10L, priya, TODAY, WorkLogStatus.DRAFT, "2");
        when(workLogRepository.findDetailedById(10L)).thenReturn(Optional.of(draft));

        assertThat(service.getById(10L, priyaCaller).status()).isEqualTo(WorkLogStatus.DRAFT);
        assertThatThrownBy(() -> service.getById(10L, adaCaller)).isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    @DisplayName("a list filter whose end is before its start is rejected")
    void searchRangeValidation() {
        assertThatThrownBy(() -> service.search(1L, null, TODAY, TODAY.minusDays(1), null, true,
                org.springframework.data.domain.Pageable.unpaged()))
                .isInstanceOf(BadRequestException.class);
    }

    /* ------------------------------- summaries ------------------------------- */

    @Test
    @DisplayName("my summary: totals come from submitted days, drafts are only counted separately, default range is 7 days")
    void mySummary() {
        when(workLogRepository.submittedDayTotals(eq(1L), eq(TODAY.minusDays(6)), eq(TODAY), eq(WorkLogStatus.DRAFT)))
                .thenReturn(List.of(day(TODAY.minusDays(1), "6.50", 2), day(TODAY, "2.00", 1)));
        when(workLogRepository.statusCountsForEmployee(1L, TODAY.minusDays(6), TODAY))
                .thenReturn(List.of(statusRow(WorkLogStatus.COMPLETED, 3), statusRow(WorkLogStatus.DRAFT, 2)));

        WorkLogSummaryResponse summary = service.mySummary(priyaCaller, null, null);

        assertThat(summary.from()).isEqualTo(TODAY.minusDays(6));
        assertThat(summary.to()).isEqualTo(TODAY);
        assertThat(summary.totalHours()).isEqualByComparingTo("8.50");
        assertThat(summary.entryCount()).isEqualTo(3);
        assertThat(summary.daysReported()).isEqualTo(2);
        assertThat(summary.draftCount()).isEqualTo(2);
        assertThat(summary.statusCounts()).containsEntry("COMPLETED", 3L).containsEntry("BLOCKED", 0L);
    }

    @Test
    @DisplayName("summary ranges must be ordered and at most 366 days")
    void summaryRangeValidation() {
        assertThatThrownBy(() -> service.mySummary(priyaCaller, TODAY, TODAY.minusDays(1)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.mySummary(priyaCaller, TODAY.minusDays(366), TODAY))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.teamSummary(TODAY.minusDays(400), TODAY, null))
                .isInstanceOf(BadRequestException.class);
    }

    /* ------------------------------ missing reports ------------------------------ */

    @Test
    @DisplayName("missing days skip weekends, approved leave, days before joining, submitted days and today")
    void missingSubmissions() {
        LocalDate monday = LocalDate.of(2026, 2, 23);
        User newcomer = user(5L, "Nora Newcomer", Role.EMPLOYEE, LocalDate.of(2026, 3, 2));
        when(userRepository.findByStatusAndRoleOrderByFullNameAsc(EmploymentStatus.ACTIVE, Role.EMPLOYEE))
                .thenReturn(List.of(newcomer, priya));
        // Priya reported Monday and Tuesday of the first week
        when(workLogRepository.submittedDays(eq(monday), eq(TODAY.minusDays(1)), eq(WorkLogStatus.DRAFT)))
                .thenReturn(List.of(submitted(1L, monday), submitted(1L, monday.plusDays(1))));
        // ... and was on approved leave Wednesday and Thursday
        LeaveRequest leave = new LeaveRequest(priya, LeaveType.EARNED, monday.plusDays(2), monday.plusDays(3), 2, "Trip");
        when(leaveRepository.findTouching(eq(LeaveStatus.APPROVED), eq(monday), eq(TODAY.minusDays(1))))
                .thenReturn(List.of(leave));

        // The requested end (10 March) is in the future: it is cut back to yesterday (Tuesday 3 March).
        List<MissingSubmissionResponse> missing = service.missingSubmissions(monday, LocalDate.of(2026, 3, 10), null);

        assertThat(missing).hasSize(2);
        MissingSubmissionResponse nora = missing.get(0);
        assertThat(nora.employeeName()).isEqualTo("Nora Newcomer");
        assertThat(nora.missingDates()).containsExactly(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 3));
        MissingSubmissionResponse priyaRow = missing.get(1);
        // Fri 27 Feb, Mon 2 Mar, Tue 3 Mar. Not Sat/Sun, not the leave days, not the reported days, not today.
        assertThat(priyaRow.missingDates()).containsExactly(
                LocalDate.of(2026, 2, 27), LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 3));
        assertThat(priyaRow.missingCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("an employee filter narrows the missing list; a range that starts today has nothing to check")
    void missingFilters() {
        when(userRepository.findByStatusAndRoleOrderByFullNameAsc(EmploymentStatus.ACTIVE, Role.EMPLOYEE))
                .thenReturn(List.of(kenji, priya));

        List<MissingSubmissionResponse> onlyKenji = service.missingSubmissions(
                LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 3), 2L);

        assertThat(onlyKenji).extracting(MissingSubmissionResponse::employeeId).containsExactly(2L);
        assertThat(onlyKenji.get(0).missingDates())
                .containsExactly(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 3));

        assertThat(service.missingSubmissions(TODAY, TODAY, null)).isEmpty();
    }

    @Test
    @DisplayName("the missing-report range is limited to 62 days")
    void missingRangeLimit() {
        assertThatThrownBy(() -> service.missingSubmissions(TODAY.minusDays(70), TODAY.minusDays(1), null))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(leaveRepository);
    }

    /* -------------------------------- helpers -------------------------------- */

    private static WorkLogRequest request(LocalDate date, String title, String description, String hours,
                                          WorkLogStatus status) {
        return new WorkLogRequest(date, title, description, new BigDecimal(hours), status);
    }

    private static DailyWorkLog log(Long id, User owner, LocalDate date, WorkLogStatus status, String hours) {
        DailyWorkLog log = new DailyWorkLog(owner, date, "Existing task", "Existing description",
                new BigDecimal(hours), status);
        ReflectionTestUtils.setField(log, "id", id);
        return log;
    }

    private static User user(Long id, String name, Role role, LocalDate joined) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setFullName(name);
        user.setEmail(name.toLowerCase().replace(' ', '.') + "@example.com");
        user.setDepartment("Engineering");
        user.setJobTitle("Developer");
        user.setJoiningDate(joined);
        user.setRole(role);
        user.setStatus(EmploymentStatus.ACTIVE);
        return user;
    }

    private static DailyWorkLogRepository.DayRow day(LocalDate date, String hours, long entries) {
        return new DailyWorkLogRepository.DayRow() {
            @Override public LocalDate getWorkDate() { return date; }
            @Override public BigDecimal getHours() { return new BigDecimal(hours); }
            @Override public long getEntryCount() { return entries; }
        };
    }

    private static DailyWorkLogRepository.StatusRow statusRow(WorkLogStatus status, long total) {
        return new DailyWorkLogRepository.StatusRow() {
            @Override public WorkLogStatus getStatus() { return status; }
            @Override public long getTotal() { return total; }
        };
    }

    private static DailyWorkLogRepository.SubmittedDayRow submitted(Long employeeId, LocalDate date) {
        return new DailyWorkLogRepository.SubmittedDayRow() {
            @Override public Long getEmployeeId() { return employeeId; }
            @Override public LocalDate getWorkDate() { return date; }
        };
    }

    /* ------------------------------ audit trail ------------------------------ */

    @Test
    @DisplayName("creating, editing and deleting a draft each write one audit record, with no description text in it")
    void changesAreAudited() {
        service.create(priyaCaller, request(TODAY, "Secret project", "Confidential description", "2", WorkLogStatus.DRAFT));
        ArgumentCaptor<String> createSummary = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(AuditActions.WORKLOG_CREATED), eq("DailyWorkLog"), any(), eq(AuditOutcome.SUCCESS), createSummary.capture());
        assertThat(createSummary.getValue()).doesNotContain("Secret").doesNotContain("Confidential");

        DailyWorkLog log = log(10L, priya, TODAY, WorkLogStatus.DRAFT, "1");
        when(workLogRepository.findDetailedById(10L)).thenReturn(Optional.of(log));
        service.update(10L, priyaCaller, request(TODAY, "Renamed", "Changed text", "2", WorkLogStatus.IN_PROGRESS));
        verify(auditService).record(eq(AuditActions.WORKLOG_UPDATED), eq("DailyWorkLog"), eq(10L), eq(AuditOutcome.SUCCESS), anyString());

        DailyWorkLog draft = log(11L, priya, TODAY, WorkLogStatus.DRAFT, "1");
        when(workLogRepository.findDetailedById(11L)).thenReturn(Optional.of(draft));
        service.delete(11L, priyaCaller);
        verify(auditService).record(eq(AuditActions.WORKLOG_DELETED), eq("DailyWorkLog"), eq(11L), eq(AuditOutcome.SUCCESS), anyString());
    }

    @Test
    @DisplayName("a refused change (not the owner, not a draft) writes no success audit record")
    void refusedChangesAreNotAuditedAsSuccess() {
        DailyWorkLog log = log(10L, priya, TODAY, WorkLogStatus.COMPLETED, "3");
        when(workLogRepository.findDetailedById(10L)).thenReturn(Optional.of(log));

        assertThatThrownBy(() -> service.delete(10L, priyaCaller)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.update(10L, kenjiCaller,
                request(TODAY, "Hijack", "Nope nope", "1", WorkLogStatus.COMPLETED)))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(auditService, never()).record(anyString(), anyString(), any(), any(), anyString());
    }
}
