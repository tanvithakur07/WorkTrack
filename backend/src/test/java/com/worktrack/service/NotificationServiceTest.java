package com.worktrack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.worktrack.entity.AuditOutcome;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveType;
import com.worktrack.entity.Notification;
import com.worktrack.entity.NotificationStatus;
import com.worktrack.entity.NotificationType;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;
import com.worktrack.exception.ConflictException;
import com.worktrack.exception.ForbiddenOperationException;
import com.worktrack.exception.ResourceNotFoundException;
import com.worktrack.repository.NotificationRepository;
import com.worktrack.repository.UserRepository;
import com.worktrack.security.CurrentUser;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-04T09:00:00Z");

    @Mock
    private NotificationRepository repository;
    @Mock
    private UserRepository users;
    @Mock
    private AuditService auditService;

    private NotificationService service;
    private User employee;
    private User admin;
    private User secondAdmin;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository, users, NotificationProperties.of(6, 60, 360, 120, 20), auditService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        employee = user(1L, "Priya Nair", "priya@example.com", Role.EMPLOYEE, EmploymentStatus.ACTIVE);
        admin = user(2L, "Ada Admin", "ada@example.com", Role.ADMIN, EmploymentStatus.ACTIVE);
        secondAdmin = user(3L, "Ben Boss", "ben@example.com", Role.ADMIN, EmploymentStatus.ACTIVE);
        when(users.findByStatusAndRoleOrderByFullNameAsc(EmploymentStatus.ACTIVE, Role.ADMIN)).thenReturn(List.of(admin, secondAdmin));
    }

    private LeaveRequest leave(long id, User owner) {
        LeaveRequest leave = new LeaveRequest(owner, LeaveType.CASUAL, LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 11), 3, "Family event");
        ReflectionTestUtils.setField(leave, "id", id);
        return leave;
    }

    private List<Notification> saved(int expected) {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("a submitted request is queued for EVERY active administrator, each with its own key, and not for the employee")
    void submissionGoesToAllAdmins() {
        service.enqueueLeaveSubmitted(leave(42L, employee));

        List<Notification> rows = saved(2);
        assertThat(rows).extracting(Notification::getRecipientEmail).containsExactly("ada@example.com", "ben@example.com");
        assertThat(rows).extracting(Notification::getDedupeKey).containsExactly("LEAVE_SUBMITTED:42:2", "LEAVE_SUBMITTED:42:3");
        assertThat(rows).allSatisfy(n -> {
            assertThat(n.getType()).isEqualTo(NotificationType.LEAVE_SUBMITTED);
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
            assertThat(n.getLeaveRequestId()).isEqualTo(42L);
            assertThat(n.getBodyText()).contains("Priya Nair").contains("EMP-0001").contains("Family event")
                    .contains("https://worktrack.example.test/leaves.html");
        });
        assertThat(rows.get(0).getBodyText()).contains("Hello Ada Admin");
        assertThat(rows.get(1).getBodyText()).contains("Hello Ben Boss");
    }

    @Test
    @DisplayName("inactive administrators and administrators without a valid address are skipped; so is the submitter themselves")
    void submissionSkipsUnsuitableAdmins() {
        User inactive = user(4L, "Old Admin", "old@example.com", Role.ADMIN, EmploymentStatus.INACTIVE);
        User noMail = user(5L, "No Mail", "not-an-address", Role.ADMIN, EmploymentStatus.ACTIVE);
        when(users.findByStatusAndRoleOrderByFullNameAsc(EmploymentStatus.ACTIVE, Role.ADMIN)).thenReturn(List.of(admin, noMail, secondAdmin));
        // the query itself only returns ACTIVE administrators, so an inactive one can never be a recipient

        service.enqueueLeaveSubmitted(leave(42L, admin)); // an administrator submits their own request

        assertThat(saved(1)).extracting(Notification::getRecipientEmail).containsExactly("ben@example.com");
        assertThat(inactive.getStatus()).isEqualTo(EmploymentStatus.INACTIVE);
    }

    @Test
    @DisplayName("with no administrator to tell, nothing is queued and nothing throws")
    void noAdmins() {
        when(users.findByStatusAndRoleOrderByFullNameAsc(EmploymentStatus.ACTIVE, Role.ADMIN)).thenReturn(List.of());
        service.enqueueLeaveSubmitted(leave(42L, employee));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("an approval goes to the employee the request belongs to, never to the administrator who decided or to anyone else")
    void approvalGoesToTheEmployee() {
        LeaveRequest approved = leave(42L, employee);
        approved.approve(admin, NOW);

        service.enqueueLeaveDecision(approved);

        List<Notification> rows = saved(1);
        assertThat(rows.get(0).getRecipientEmail()).isEqualTo("priya@example.com");
        assertThat(rows.get(0).getType()).isEqualTo(NotificationType.LEAVE_APPROVED);
        assertThat(rows.get(0).getDedupeKey()).isEqualTo("LEAVE_APPROVED:42:1");
        assertThat(rows.get(0).getBodyText()).contains("Hello Priya Nair").contains("Decided by: Ada Admin");
    }

    @Test
    @DisplayName("a rejection goes to the employee and carries the reason")
    void rejectionGoesToTheEmployeeWithReason() {
        LeaveRequest rejected = leave(43L, employee);
        rejected.reject(admin, NOW, "Team is short-staffed");

        service.enqueueLeaveDecision(rejected);

        Notification n = saved(1).get(0);
        assertThat(n.getRecipientEmail()).isEqualTo("priya@example.com");
        assertThat(n.getType()).isEqualTo(NotificationType.LEAVE_REJECTED);
        assertThat(n.getBodyText()).contains("Reason given: Team is short-staffed");
    }

    @Test
    @DisplayName("asking for a decision e-mail for a request that is still pending is a programming error")
    void pendingIsNotADecision() {
        assertThatThrownBy(() -> service.enqueueLeaveDecision(leave(42L, employee))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an employee without a usable address gets no decision e-mail, and nothing throws")
    void invalidEmployeeAddress() {
        for (String bad : new String[]{"", "   ", "no-at-sign", "two@@example.com", "spaces in@example.com", "x@nodot", "a@b.com\r\nBcc: evil@x.com"}) {
            LeaveRequest approved = leave(42L, user(7L, "Bad Address", bad, Role.EMPLOYEE, EmploymentStatus.ACTIVE));
            approved.approve(admin, NOW);
            service.enqueueLeaveDecision(approved);
        }
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("queuing is idempotent: a message with the same key is never queued twice")
    void duplicatesAreSkipped() {
        when(repository.existsByDedupeKey("LEAVE_APPROVED:42:1")).thenReturn(true);
        LeaveRequest approved = leave(42L, employee);
        approved.approve(admin, NOW);
        service.enqueueLeaveDecision(approved);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a cancelled request tells the other administrators, not the employee who cancelled it")
    void cancellationByTheEmployee() {
        service.enqueueLeaveCancelled(leave(42L, employee), employee.getId());

        assertThat(saved(2)).extracting(Notification::getRecipientEmail).containsExactly("ada@example.com", "ben@example.com");
    }

    @Test
    @DisplayName("an invitation is queued for the new employee with a placeholder instead of any link, and about that employee")
    void invitation() {
        NotificationService.InvitationResult result = service.enqueueEmployeeInvited(employee, false);

        assertThat(result).isEqualTo(NotificationService.InvitationResult.QUEUED);
        Notification n = saved(1).get(0);
        assertThat(n.getRecipientEmail()).isEqualTo("priya@example.com");
        assertThat(n.getType()).isEqualTo(NotificationType.EMPLOYEE_INVITED);
        assertThat(n.getDedupeKey()).isEqualTo("EMPLOYEE_INVITED:1");
        assertThat(n.getSubjectUserId()).isEqualTo(1L);
        assertThat(n.getBodyText()).contains("Hello Priya Nair").contains(NotificationService.SETUP_LINK_PLACEHOLDER)
                .contains("https://worktrack.example.test/login.html").contains("Nobody will ever e-mail you a password");
        assertThat(n.getBodyHtml()).contains(NotificationService.SETUP_LINK_PLACEHOLDER).doesNotContain("token=");
    }

    @Test
    @DisplayName("an invitation is not queued twice while one is waiting, nor for inactive accounts or invalid addresses")
    void invitationGuards() {
        when(repository.existsBySubjectUserIdAndTypeAndStatusIn(eq(1L), eq(NotificationType.EMPLOYEE_INVITED), any())).thenReturn(true);
        assertThat(service.enqueueEmployeeInvited(employee, true)).isEqualTo(NotificationService.InvitationResult.ALREADY_QUEUED);

        User inactive = user(8L, "Gone", "gone@example.com", Role.EMPLOYEE, EmploymentStatus.INACTIVE);
        assertThat(service.enqueueEmployeeInvited(inactive, false)).isEqualTo(NotificationService.InvitationResult.SKIPPED_INACTIVE_ACCOUNT);
        User bad = user(9L, "Bad", "bad", Role.EMPLOYEE, EmploymentStatus.ACTIVE);
        assertThat(service.enqueueEmployeeInvited(bad, false)).isEqualTo(NotificationService.InvitationResult.SKIPPED_INVALID_EMAIL);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a profile update tells the employee which fields changed; an e-mail change also warns the OLD address")
    void profileUpdate() {
        employee.setEmail("priya.new@example.com");

        service.enqueueEmployeeUpdated(employee, List.of("department", "e-mail address"), "priya@example.com", 2L);

        List<Notification> rows = saved(2);
        assertThat(rows.get(0).getRecipientEmail()).isEqualTo("priya.new@example.com");
        assertThat(rows.get(0).getBodyText()).contains("What changed: department, e-mail address");
        assertThat(rows.get(1).getRecipientEmail()).isEqualTo("priya@example.com");
        assertThat(rows.get(1).getType()).isEqualTo(NotificationType.ACCOUNT_SECURITY);
    }

    @Test
    @DisplayName("nothing is sent for an update that changed nothing, or about your own action")
    void profileUpdateGuards() {
        service.enqueueEmployeeUpdated(employee, List.of(), "priya@example.com", 2L);
        service.enqueueEmployeeUpdated(employee, List.of("department"), "priya@example.com", employee.getId());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("deactivation tells the employee and the OTHER active administrators, not the one who did it")
    void deactivation() {
        service.enqueueEmployeeStatusChanged(employee, false, admin.getId());

        List<Notification> rows = saved(2);
        assertThat(rows).extracting(Notification::getRecipientEmail).containsExactly("priya@example.com", "ben@example.com");
        assertThat(rows).extracting(Notification::getType).containsOnly(NotificationType.EMPLOYEE_DEACTIVATED);
        assertThat(rows.get(0).getBodyText()).contains("deactivated").contains("can no longer sign in");
    }

    @Test
    @DisplayName("reactivation uses its own type and tells the employee they can sign in again")
    void reactivation() {
        service.enqueueEmployeeStatusChanged(employee, true, admin.getId());

        Notification own = saved(2).get(0);
        assertThat(own.getType()).isEqualTo(NotificationType.EMPLOYEE_REACTIVATED);
        assertThat(own.getBodyText()).contains("reactivated").contains("sign in again");
    }

    @Test
    @DisplayName("a security notice goes to the affected person only and contains no password, hash or link")
    void securityNotice() {
        service.enqueueSecurityNotice(employee, "selfpw", "Your password was changed", "The password of your account was just changed.");

        Notification n = saved(1).get(0);
        assertThat(n.getRecipientEmail()).isEqualTo("priya@example.com");
        assertThat(n.getType()).isEqualTo(NotificationType.ACCOUNT_SECURITY);
        assertThat(n.getBodyText()).doesNotContain("http");
    }

    @Test
    @DisplayName("an administrator can retry a FAILED notification and the retry is audited")
    void retryFailed() {
        Notification failed = failedNotification(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(failed));
        when(repository.requeueFailed(7L, NOW)).thenReturn(1);

        service.retry(7L, new CurrentUser(2L, Role.ADMIN));

        verify(auditService).record(eq(AuditActions.NOTIFICATION_RETRY), eq("Notification"), eq(7L), eq(AuditOutcome.SUCCESS), anyString());
    }

    @Test
    @DisplayName("only FAILED notifications can be retried; the refusal is audited in its own transaction")
    void retryOfNonFailedIsConflict() {
        Notification sent = failedNotification(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(sent));
        when(repository.requeueFailed(7L, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.retry(7L, new CurrentUser(2L, Role.ADMIN))).isInstanceOf(ConflictException.class);

        verify(auditService).recordIndependent(eq(AuditActions.NOTIFICATION_RETRY), eq("Notification"), eq(7L), eq(AuditOutcome.FAILURE), anyString());
    }

    @Test
    @DisplayName("an ordinary employee cannot retry, and an unknown id is a 404")
    void retryGuards() {
        assertThatThrownBy(() -> service.retry(7L, new CurrentUser(1L, Role.EMPLOYEE))).isInstanceOf(ForbiddenOperationException.class);
        verify(repository, never()).requeueFailed(any(), any());

        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retry(99L, new CurrentUser(2L, Role.ADMIN))).isInstanceOf(ResourceNotFoundException.class);
    }

    private Notification failedNotification(long id) {
        Notification n = new Notification("priya@example.com", NotificationType.LEAVE_APPROVED, 42L, "LEAVE_APPROVED:42:1",
                "S", "<p>x</p>", "x", NOW);
        ReflectionTestUtils.setField(n, "id", id);
        ReflectionTestUtils.setField(n, "status", NotificationStatus.FAILED);
        return n;
    }

    private static User user(Long id, String name, String email, Role role, EmploymentStatus status) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setFullName(name);
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setDepartment("Engineering");
        user.setJobTitle("Developer");
        user.setJoiningDate(LocalDate.of(2024, 1, 15));
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}
