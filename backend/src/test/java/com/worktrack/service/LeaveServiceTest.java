package com.worktrack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

import com.worktrack.dto.LeaveCreateRequest;
import com.worktrack.dto.LeaveResponse;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveStatus;
import com.worktrack.entity.LeaveType;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;
import com.worktrack.exception.BadRequestException;
import com.worktrack.exception.ConflictException;
import com.worktrack.exception.ForbiddenOperationException;
import com.worktrack.exception.ResourceNotFoundException;
import com.worktrack.repository.LeaveRequestRepository;
import com.worktrack.repository.UserRepository;
import com.worktrack.security.CurrentUser;

/** Business rules of the leave workflow, with a fixed clock: "today" is Wednesday 4 March 2026. */
@ExtendWith(MockitoExtension.class)
class LeaveServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-04T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 9);

    @Mock
    private LeaveRequestRepository leaveRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuditService auditService;

    private LeaveService service;
    private User employee;
    private User admin;
    private CurrentUser employeeCaller;
    private CurrentUser adminCaller;

    @BeforeEach
    void setUp() {
        service = new LeaveService(leaveRepository, userRepository, CLOCK, notificationService, auditService);
        employee = user(1L, "Priya Nair", Role.EMPLOYEE);
        admin = user(2L, "Ada Admin", Role.ADMIN);
        employeeCaller = new CurrentUser(1L, Role.EMPLOYEE);
        adminCaller = new CurrentUser(2L, Role.ADMIN);
        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(employee));
        lenient().when(userRepository.findById(2L)).thenReturn(Optional.of(admin));
        // submit() and approve() lock the employee's row first (see LeaveService)
        lenient().when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(employee));
        lenient().when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(admin));
        lenient().when(leaveRepository.findEmployeeIdById(anyLong())).thenReturn(Optional.of(1L));
        lenient().when(leaveRepository.save(any(LeaveRequest.class))).thenAnswer(call -> call.getArgument(0));
        lenient().when(leaveRepository.saveAndFlush(any(LeaveRequest.class))).thenAnswer(call -> call.getArgument(0));
    }

    /* ---------------------------- submitting ---------------------------- */

    @Test
    @DisplayName("rejects an end date before the start date")
    void rejectsEndBeforeStart() {
        LeaveCreateRequest request = request(LeaveType.CASUAL, MONDAY, MONDAY.minusDays(1));

        assertThatThrownBy(() -> service.submit(employeeCaller, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("End date cannot be before start date");
        verify(leaveRepository, never()).save(any());
    }

    @Test
    @DisplayName("only sick leave may start in the past")
    void rejectsPastStartForCasualLeave() {
        LeaveCreateRequest request = request(LeaveType.CASUAL, LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 4));

        assertThatThrownBy(() -> service.submit(employeeCaller, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only sick leave can start in the past");
    }

    @Test
    @DisplayName("sick leave can be backdated within 7 days")
    void allowsRecentSickLeave() {
        when(leaveRepository.existsOverlap(anyLong(), anyCollection(), any(LocalDate.class), any(LocalDate.class), anyLong()))
                .thenReturn(false);
        // Monday 2 March to Tuesday 3 March: both are before "today" (Wednesday 4 March)
        LeaveResponse response = service.submit(employeeCaller,
                request(LeaveType.SICK, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 3)));

        assertThat(response.status()).isEqualTo(LeaveStatus.PENDING);
        assertThat(response.days()).isEqualTo(2);
    }

    @Test
    @DisplayName("sick leave cannot be backdated by more than 7 days")
    void rejectsOldSickLeave() {
        LeaveCreateRequest request = request(LeaveType.SICK, LocalDate.of(2026, 2, 24), LocalDate.of(2026, 2, 25));

        assertThatThrownBy(() -> service.submit(employeeCaller, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("backdated");
    }

    @Test
    @DisplayName("a request that covers only a weekend is rejected")
    void rejectsWeekendOnlyRequest() {
        // Saturday 14 and Sunday 15 March
        LeaveCreateRequest request = request(LeaveType.CASUAL, MONDAY.plusDays(5), MONDAY.plusDays(6));

        assertThatThrownBy(() -> service.submit(employeeCaller, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("weekend");
    }

    @Test
    @DisplayName("a single request cannot cover more than 60 calendar days")
    void rejectsVeryLongRequest() {
        LeaveCreateRequest request = request(LeaveType.EARNED, MONDAY, MONDAY.plusDays(60)); // 61 days

        assertThatThrownBy(() -> service.submit(employeeCaller, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("60");
    }

    @Test
    @DisplayName("a request that overlaps the employee's pending or approved leave is a conflict")
    void rejectsOverlap() {
        when(leaveRepository.existsOverlap(anyLong(), anyCollection(), any(LocalDate.class), any(LocalDate.class), anyLong()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.submit(employeeCaller, request(LeaveType.CASUAL, MONDAY, MONDAY.plusDays(2))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("overlaps");
        verify(leaveRepository, never()).save(any());
    }

    @Test
    @DisplayName("leave days exclude weekends, and the saved request starts as PENDING")
    void countsWorkingDaysAndSavesPending() {
        when(leaveRepository.existsOverlap(anyLong(), anyCollection(), any(LocalDate.class), any(LocalDate.class), anyLong()))
                .thenReturn(false);

        // Monday 9 March to Monday 16 March = 5 weekdays + 1 weekday
        service.submit(employeeCaller, request(LeaveType.EARNED, MONDAY, MONDAY.plusDays(7)));

        ArgumentCaptor<LeaveRequest> saved = ArgumentCaptor.forClass(LeaveRequest.class);
        verify(leaveRepository).save(saved.capture());
        assertThat(saved.getValue().getDays()).isEqualTo(6);
        assertThat(saved.getValue().getStatus()).isEqualTo(LeaveStatus.PENDING);
        assertThat(saved.getValue().getEmployee()).isSameAs(employee);
        assertThat(saved.getValue().getReason()).isEqualTo("Family event");
    }

    @Test
    @DisplayName("inactive accounts cannot submit requests")
    void rejectsInactiveEmployee() {
        employee.setStatus(EmploymentStatus.INACTIVE);

        assertThatThrownBy(() -> service.submit(employeeCaller, request(LeaveType.CASUAL, MONDAY, MONDAY)))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    /* ---------------------------- approving / rejecting ---------------------------- */

    @Test
    @DisplayName("an administrator can approve a pending request, and who/when is recorded")
    void approvesPendingRequest() {
        LeaveRequest leave = pendingLeave(10L, employee);
        when(leaveRepository.findDetailedById(10L)).thenReturn(Optional.of(leave));
        when(leaveRepository.existsOverlap(anyLong(), anyCollection(), any(LocalDate.class), any(LocalDate.class), anyLong()))
                .thenReturn(false);

        LeaveResponse response = service.approve(10L, adminCaller);

        assertThat(response.status()).isEqualTo(LeaveStatus.APPROVED);
        assertThat(leave.getReviewedBy()).isSameAs(admin);
        assertThat(leave.getReviewedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("approval is refused when it would overlap leave that is already approved")
    void approvalChecksOverlapWithApprovedLeave() {
        LeaveRequest leave = pendingLeave(10L, employee);
        when(leaveRepository.findDetailedById(10L)).thenReturn(Optional.of(leave));
        when(leaveRepository.existsOverlap(anyLong(), anyCollection(), any(LocalDate.class), any(LocalDate.class), anyLong()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.approve(10L, adminCaller)).isInstanceOf(ConflictException.class);
        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test
    @DisplayName("an administrator cannot approve or reject their own request")
    void adminCannotReviewOwnRequest() {
        LeaveRequest ownLeave = pendingLeave(11L, admin);
        when(leaveRepository.findDetailedById(11L)).thenReturn(Optional.of(ownLeave));

        assertThatThrownBy(() -> service.approve(11L, adminCaller))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("your own");
        assertThatThrownBy(() -> service.reject(11L, "No", adminCaller))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(ownLeave.getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test
    @DisplayName("an employee cannot approve or reject anything")
    void employeeCannotReview() {
        assertThatThrownBy(() -> service.approve(10L, employeeCaller)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.reject(10L, "No", employeeCaller)).isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    @DisplayName("only pending requests can be approved or rejected")
    void onlyPendingCanBeReviewed() {
        LeaveRequest cancelled = pendingLeave(12L, employee);
        cancelled.cancel();
        when(leaveRepository.findDetailedById(12L)).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> service.approve(12L, adminCaller)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.reject(12L, "Too late", adminCaller)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("rejecting stores the reason, the reviewer and the time")
    void rejectsWithReason() {
        LeaveRequest leave = pendingLeave(13L, employee);
        when(leaveRepository.findDetailedById(13L)).thenReturn(Optional.of(leave));

        LeaveResponse response = service.reject(13L, "  Team is short-staffed  ", adminCaller);

        assertThat(response.status()).isEqualTo(LeaveStatus.REJECTED);
        assertThat(response.rejectionReason()).isEqualTo("Team is short-staffed");
        assertThat(leave.getReviewedBy()).isSameAs(admin);
        assertThat(leave.getReviewedAt()).isEqualTo(NOW);
    }

    /* ---------------------------- cancelling and viewing ---------------------------- */

    @Test
    @DisplayName("an employee can cancel their own pending request")
    void ownerCancelsPending() {
        LeaveRequest leave = pendingLeave(14L, employee);
        when(leaveRepository.findDetailedById(14L)).thenReturn(Optional.of(leave));

        assertThat(service.cancel(14L, employeeCaller).status()).isEqualTo(LeaveStatus.CANCELLED);
    }

    @Test
    @DisplayName("nobody can cancel someone else's request, not even an administrator")
    void cannotCancelOthersRequest() {
        LeaveRequest leave = pendingLeave(15L, employee);
        when(leaveRepository.findDetailedById(15L)).thenReturn(Optional.of(leave));

        assertThatThrownBy(() -> service.cancel(15L, adminCaller)).isInstanceOf(ForbiddenOperationException.class);
        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test
    @DisplayName("a request that is no longer pending cannot be cancelled")
    void cannotCancelApprovedRequest() {
        LeaveRequest leave = pendingLeave(16L, employee);
        leave.approve(admin, NOW);
        when(leaveRepository.findDetailedById(16L)).thenReturn(Optional.of(leave));

        assertThatThrownBy(() -> service.cancel(16L, employeeCaller)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("an employee can only view their own request; an administrator can view any")
    void viewingRules() {
        User other = user(3L, "Arjun Mehta", Role.EMPLOYEE);
        LeaveRequest othersLeave = pendingLeave(17L, other);
        when(leaveRepository.findDetailedById(17L)).thenReturn(Optional.of(othersLeave));

        assertThatThrownBy(() -> service.getById(17L, employeeCaller)).isInstanceOf(ForbiddenOperationException.class);
        assertThat(service.getById(17L, adminCaller).employeeId()).isEqualTo(3L);
    }

    /* ---------------------------- concurrency: lock order ---------------------------- */

    @Test
    @DisplayName("submit locks the employee's row before it checks for overlap and before it saves")
    void submitLocksEmployeeBeforeOverlapCheck() {
        when(leaveRepository.existsOverlap(anyLong(), anyCollection(), any(LocalDate.class), any(LocalDate.class), anyLong()))
                .thenReturn(false);

        service.submit(employeeCaller, request(LeaveType.CASUAL, MONDAY, MONDAY.plusDays(1)));

        InOrder order = inOrder(userRepository, leaveRepository);
        order.verify(userRepository).findByIdForUpdate(1L);
        order.verify(leaveRepository).existsOverlap(anyLong(), anyCollection(), any(LocalDate.class),
                any(LocalDate.class), anyLong());
        order.verify(leaveRepository).save(any(LeaveRequest.class));
    }

    @Test
    @DisplayName("approve locks the employee's row first and only then loads the request and checks overlap")
    void approveLocksEmployeeBeforeReadingTheRequest() {
        LeaveRequest leave = pendingLeave(10L, employee);
        when(leaveRepository.findDetailedById(10L)).thenReturn(Optional.of(leave));
        when(leaveRepository.existsOverlap(anyLong(), anyCollection(), any(LocalDate.class), any(LocalDate.class), anyLong()))
                .thenReturn(false);

        service.approve(10L, adminCaller);

        InOrder order = inOrder(leaveRepository, userRepository);
        order.verify(leaveRepository).findEmployeeIdById(10L);
        order.verify(userRepository).findByIdForUpdate(1L);
        order.verify(leaveRepository).findDetailedById(10L);
        order.verify(leaveRepository).existsOverlap(anyLong(), anyCollection(), any(LocalDate.class),
                any(LocalDate.class), anyLong());
    }

    @Test
    @DisplayName("approving a request that does not exist is a 404 and takes no lock")
    void approveUnknownRequest() {
        when(leaveRepository.findEmployeeIdById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(99L, adminCaller)).isInstanceOf(ResourceNotFoundException.class);
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }

    /* ---------------------------- helpers ---------------------------- */

    private static LeaveCreateRequest request(LeaveType type, LocalDate start, LocalDate end) {
        return new LeaveCreateRequest(type, start, end, "Family event");
    }

    private static User user(Long id, String name, Role role) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setFullName(name);
        user.setEmail(name.toLowerCase().replace(' ', '.') + "@example.com");
        user.setDepartment("Engineering");
        user.setJobTitle("Developer");
        user.setJoiningDate(LocalDate.of(2024, 1, 15));
        user.setRole(role);
        user.setStatus(EmploymentStatus.ACTIVE);
        return user;
    }

    private static LeaveRequest pendingLeave(Long id, User owner) {
        LeaveRequest leave = new LeaveRequest(owner, LeaveType.CASUAL, MONDAY, MONDAY.plusDays(2), 3, "Personal");
        ReflectionTestUtils.setField(leave, "id", id);
        return leave;
    }

    /* ---------------------------- notifications and audit ---------------------------- */

    @Test
    @DisplayName("submitting queues the confirmation e-mail and an audit record in the same call")
    void submitQueuesConfirmationAndAudit() {
        when(leaveRepository.existsOverlap(anyLong(), anyCollection(), any(LocalDate.class), any(LocalDate.class), anyLong()))
                .thenReturn(false);

        service.submit(employeeCaller, request(LeaveType.CASUAL, MONDAY, MONDAY));

        ArgumentCaptor<LeaveRequest> saved = ArgumentCaptor.forClass(LeaveRequest.class);
        verify(notificationService).enqueueLeaveSubmitted(saved.capture());
        assertThat(saved.getValue().getEmployee()).isSameAs(employee);
        verify(auditService).record(eq(AuditActions.LEAVE_SUBMITTED), eq("LeaveRequest"), any(), eq(AuditOutcome.SUCCESS), anyString());
    }

    @Test
    @DisplayName("a submission that fails validation queues nothing and audits nothing")
    void failedSubmissionQueuesNothing() {
        assertThatThrownBy(() -> service.submit(employeeCaller, request(LeaveType.CASUAL, MONDAY, MONDAY.minusDays(1))))
                .isInstanceOf(BadRequestException.class);

        verify(notificationService, never()).enqueueLeaveSubmitted(any());
        verify(auditService, never()).record(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("approving queues the decision e-mail and an audit record")
    void approvalQueuesDecisionAndAudit() {
        LeaveRequest leave = pendingLeave(10L, employee);
        when(leaveRepository.findDetailedById(10L)).thenReturn(Optional.of(leave));
        when(leaveRepository.existsOverlap(anyLong(), anyCollection(), any(LocalDate.class), any(LocalDate.class), anyLong()))
                .thenReturn(false);

        service.approve(10L, adminCaller);

        verify(notificationService).enqueueLeaveDecision(leave);
        verify(auditService).record(eq(AuditActions.LEAVE_APPROVED), eq("LeaveRequest"), eq(10L), eq(AuditOutcome.SUCCESS), anyString());
    }

    @Test
    @DisplayName("rejecting queues the decision e-mail, and the audit text does not contain the reason")
    void rejectionQueuesDecisionAndAuditWithoutReasonText() {
        LeaveRequest leave = pendingLeave(13L, employee);
        when(leaveRepository.findDetailedById(13L)).thenReturn(Optional.of(leave));

        service.reject(13L, "Confidential staffing reason", adminCaller);

        verify(notificationService).enqueueLeaveDecision(leave);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(AuditActions.LEAVE_REJECTED), eq("LeaveRequest"), eq(13L), eq(AuditOutcome.SUCCESS), summary.capture());
        assertThat(summary.getValue()).doesNotContain("Confidential");
    }

    @Test
    @DisplayName("a refused approval (own request) queues no e-mail and writes no success audit line")
    void refusedApprovalQueuesNothing() {
        LeaveRequest own = pendingLeave(14L, admin);
        when(leaveRepository.findEmployeeIdById(14L)).thenReturn(Optional.of(2L));
        when(leaveRepository.findDetailedById(14L)).thenReturn(Optional.of(own));

        assertThatThrownBy(() -> service.approve(14L, adminCaller)).isInstanceOf(ForbiddenOperationException.class);

        verify(notificationService, never()).enqueueLeaveDecision(any());
        verify(auditService, never()).record(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("cancelling writes an audit record and queues no e-mail")
    void cancelAuditsWithoutEmail() {
        LeaveRequest leave = pendingLeave(15L, employee);
        when(leaveRepository.findDetailedById(15L)).thenReturn(Optional.of(leave));

        service.cancel(15L, employeeCaller);

        verify(auditService).record(eq(AuditActions.LEAVE_CANCELLED), eq("LeaveRequest"), eq(15L), eq(AuditOutcome.SUCCESS), anyString());
        verify(notificationService, never()).enqueueLeaveDecision(any());
    }
}
