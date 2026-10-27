package com.worktrack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.worktrack.dto.ChangePasswordRequest;
import com.worktrack.dto.CreateEmployeeRequest;
import com.worktrack.dto.EmployeeResponse;
import com.worktrack.dto.UpdateEmployeeRequest;
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

@ExtendWith(MockitoExtension.class)
class EmployeeServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private LeaveRequestRepository leaveRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditService auditService;
    @Mock
    private NotificationService notificationService;

    private EmployeeService service;

    @BeforeEach
    void setUp() {
        service = new EmployeeService(userRepository, leaveRepository, passwordEncoder, auditService, notificationService);
        lenient().when(notificationService.enqueueEmployeeInvited(any(User.class), anyBoolean()))
                .thenReturn(NotificationService.InvitationResult.QUEUED);
    }

    @Test
    @DisplayName("creating an employee trims and lower-cases fields, hashes the password and applies defaults")
    void createNormalisesAndHashes() {
        when(userRepository.existsByEmailIgnoreCase("test.person@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Passw0rd1")).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenAnswer(call -> {
            User saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 5L);
            return saved;
        });

        EmployeeResponse response = service.create(new CreateEmployeeRequest(
                "  Test Person ", " Test.Person@Example.com ", "Passw0rd1", " Engineering ", " Developer ",
                LocalDate.of(2026, 1, 5), null, null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getFullName()).isEqualTo("Test Person");
        assertThat(saved.getEmail()).isEqualTo("test.person@example.com");
        assertThat(saved.getPasswordHash()).isEqualTo("hashed-value").isNotEqualTo("Passw0rd1");
        assertThat(saved.getDepartment()).isEqualTo("Engineering");
        assertThat(saved.getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(saved.getStatus()).isEqualTo(EmploymentStatus.ACTIVE);
        assertThat(response.employeeCode()).isEqualTo("EMP-0005");
    }

    @Test
    @DisplayName("creating an employee with an existing email is a conflict")
    void createRejectsDuplicateEmail() {
        when(userRepository.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateEmployeeRequest(
                "Someone", "taken@example.com", "Passw0rd1", "Sales", "Rep", LocalDate.of(2026, 1, 5), null, null)))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("an administrator cannot remove their own admin role")
    void adminCannotDemoteSelf() {
        User self = user(1L, Role.ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(self));
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("user1@example.com", 1L)).thenReturn(false);

        UpdateEmployeeRequest request = new UpdateEmployeeRequest("User 1", "user1@example.com", null,
                "Engineering", "Developer", LocalDate.of(2024, 1, 15), Role.EMPLOYEE, EmploymentStatus.ACTIVE);

        assertThatThrownBy(() -> service.update(1L, request, new CurrentUser(1L, Role.ADMIN)))
                .isInstanceOf(ConflictException.class);
        assertThat(self.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("an administrator cannot deactivate their own account")
    void adminCannotDeactivateSelf() {
        assertThatThrownBy(() -> service.deactivate(1L, new CurrentUser(1L, Role.ADMIN)))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("deactivating keeps the record, sets INACTIVE and cancels pending leave requests")
    void deactivateCancelsPendingLeaves() {
        User target = user(2L, Role.EMPLOYEE);
        LeaveRequest pending = new LeaveRequest(target, LeaveType.CASUAL, LocalDate.of(2026, 3, 9),
                LocalDate.of(2026, 3, 10), 2, "Personal");
        List<LeaveRequest> pendingList = List.of(pending);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(leaveRepository.findByEmployeeIdAndStatus(2L, LeaveStatus.PENDING)).thenReturn(pendingList);

        service.deactivate(2L, new CurrentUser(1L, Role.ADMIN));

        assertThat(target.getStatus()).isEqualTo(EmploymentStatus.INACTIVE);
        assertThat(pending.getStatus()).isEqualTo(LeaveStatus.CANCELLED);
        verify(leaveRepository).saveAll(pendingList);
        verify(userRepository).saveAndFlush(target);
    }

    @Test
    @DisplayName("an employee cannot read another employee's profile")
    void employeeCannotViewOthers() {
        assertThatThrownBy(() -> service.getById(3L, new CurrentUser(1L, Role.EMPLOYEE)))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("an employee can read their own profile, an administrator any profile")
    void allowedProfileReads() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, Role.EMPLOYEE)));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user(3L, Role.EMPLOYEE)));

        assertThat(service.getById(1L, new CurrentUser(1L, Role.EMPLOYEE)).id()).isEqualTo(1L);
        assertThat(service.getById(3L, new CurrentUser(9L, Role.ADMIN)).id()).isEqualTo(3L);
    }

    @Test
    @DisplayName("an unknown employee is a 404")
    void unknownEmployee() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L, new CurrentUser(9L, Role.ADMIN)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("changing the password requires the correct current password")
    void changePasswordChecksCurrent() {
        User user = user(1L, Role.EMPLOYEE);
        user.setPasswordHash("stored-hash");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-one1", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(1L, new ChangePasswordRequest("wrong-one1", "Brand-new1")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Current password is incorrect");
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a correct current password lets the new password be stored hashed")
    void changePasswordStoresHash() {
        User user = user(1L, Role.EMPLOYEE);
        user.setPasswordHash("stored-hash");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Old-pass1", "stored-hash")).thenReturn(true);
        when(passwordEncoder.encode("Brand-new1")).thenReturn("new-hash");

        service.changePassword(1L, new ChangePasswordRequest("Old-pass1", "Brand-new1"));

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).saveAndFlush(user);
    }

    /* ------------------------------ audit trail ------------------------------ */

    @Test
    @DisplayName("creating an employee writes an audit record that does not contain the password")
    void createIsAudited() {
        when(userRepository.existsByEmailIgnoreCase("new.person@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Passw0rd1")).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenAnswer(call -> {
            User saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 9L);
            return saved;
        });

        service.create(new CreateEmployeeRequest("New Person", "new.person@example.com", "Passw0rd1", "Sales",
                "Executive", LocalDate.of(2026, 1, 5), null, null));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(AuditActions.EMPLOYEE_CREATED), eq("User"), eq(9L), eq(AuditOutcome.SUCCESS), summary.capture());
        assertThat(summary.getValue()).doesNotContain("Passw0rd1").doesNotContain("hashed-value");
    }

    @Test
    @DisplayName("a role change, a deactivation and a password reset through update are each audited separately")
    void updateAuditsRoleChangeDeactivationAndPasswordReset() {
        User target = user(2L, Role.EMPLOYEE);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("user2@example.com", 2L)).thenReturn(false);
        when(passwordEncoder.encode("Another-pass1")).thenReturn("another-hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(leaveRepository.findByEmployeeIdAndStatus(2L, LeaveStatus.PENDING)).thenReturn(List.of());

        service.update(2L, new UpdateEmployeeRequest("User 2", "user2@example.com", "Another-pass1", "Engineering",
                "Developer", LocalDate.of(2024, 1, 15), Role.ADMIN, EmploymentStatus.INACTIVE), new CurrentUser(1L, Role.ADMIN));

        verify(auditService).record(eq(AuditActions.EMPLOYEE_UPDATED), eq("User"), eq(2L), eq(AuditOutcome.SUCCESS), anyString());
        verify(auditService).record(eq(AuditActions.ROLE_CHANGED), eq("User"), eq(2L), eq(AuditOutcome.SUCCESS), eq("role EMPLOYEE -> ADMIN"));
        verify(auditService).record(eq(AuditActions.EMPLOYEE_DEACTIVATED), eq("User"), eq(2L), eq(AuditOutcome.SUCCESS), anyString());
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(AuditActions.PASSWORD_CHANGED), eq("User"), eq(2L), eq(AuditOutcome.SUCCESS), summary.capture());
        assertThat(summary.getValue()).doesNotContain("Another-pass1").doesNotContain("another-hash");
    }

    @Test
    @DisplayName("an update that changes nothing about the role writes no role-change record")
    void updateWithoutRoleChangeWritesNoRoleRecord() {
        User target = user(2L, Role.EMPLOYEE);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("user2@example.com", 2L)).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.update(2L, new UpdateEmployeeRequest("User 2", "user2@example.com", null, "Sales",
                "Developer", LocalDate.of(2024, 1, 15), Role.EMPLOYEE, EmploymentStatus.ACTIVE), new CurrentUser(1L, Role.ADMIN));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(AuditActions.EMPLOYEE_UPDATED), eq("User"), eq(2L), eq(AuditOutcome.SUCCESS), summary.capture());
        assertThat(summary.getValue()).isEqualTo("changed: department");
        verify(auditService, never()).record(eq(AuditActions.ROLE_CHANGED), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("deactivating writes an audit record; a successful password change is audited without the password")
    void deactivateAndPasswordChangeAreAudited() {
        User target = user(2L, Role.EMPLOYEE);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(leaveRepository.findByEmployeeIdAndStatus(2L, LeaveStatus.PENDING)).thenReturn(List.of());
        service.deactivate(2L, new CurrentUser(1L, Role.ADMIN));
        verify(auditService).record(eq(AuditActions.EMPLOYEE_DEACTIVATED), eq("User"), eq(2L), eq(AuditOutcome.SUCCESS), anyString());

        User owner = user(1L, Role.EMPLOYEE);
        owner.setPasswordHash("stored-hash");
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(passwordEncoder.matches("Old-pass1", "stored-hash")).thenReturn(true);
        when(passwordEncoder.encode("Brand-new1")).thenReturn("new-hash");
        service.changePassword(1L, new ChangePasswordRequest("Old-pass1", "Brand-new1"));
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq(AuditActions.PASSWORD_CHANGED), eq("User"), eq(1L), eq(AuditOutcome.SUCCESS), summary.capture());
        assertThat(summary.getValue()).doesNotContain("Old-pass1").doesNotContain("Brand-new1").doesNotContain("hash");
    }

    @Test
    @DisplayName("a wrong current password writes no success record from the service")
    void wrongPasswordWritesNoSuccessRecord() {
        User user = user(1L, Role.EMPLOYEE);
        user.setPasswordHash("stored-hash");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-one1", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(1L, new ChangePasswordRequest("wrong-one1", "Brand-new1")))
                .isInstanceOf(BadRequestException.class);

        verify(auditService, never()).record(anyString(), anyString(), any(), any(), anyString());
    }

    private static User user(Long id, Role role) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setFullName("User " + id);
        user.setEmail("user" + id + "@example.com");
        user.setPasswordHash("hash");
        user.setDepartment("Engineering");
        user.setJobTitle("Developer");
        user.setJoiningDate(LocalDate.of(2024, 1, 15));
        user.setRole(role);
        user.setStatus(EmploymentStatus.ACTIVE);
        return user;
    }

    @Test
    @DisplayName("a successful creation queues exactly one invitation, for the saved employee, and reports it")
    void createQueuesOneInvitation() {
        when(userRepository.existsByEmailIgnoreCase("new.hire@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Passw0rd1")).thenReturn("hashed-value");
        when(userRepository.save(any(User.class))).thenAnswer(call -> {
            User saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 9L);
            return saved;
        });

        EmployeeResponse response = service.create(new CreateEmployeeRequest(
                "New Hire", "New.Hire@Example.com", "Passw0rd1", "Sales", "Rep", LocalDate.of(2026, 1, 5), null, null));

        ArgumentCaptor<User> invited = ArgumentCaptor.forClass(User.class);
        verify(notificationService, times(1)).enqueueEmployeeInvited(invited.capture(), eq(false));
        assertThat(invited.getValue().getEmail()).isEqualTo("new.hire@example.com");
        assertThat(response.invitation()).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("a refused creation (duplicate address) queues no invitation")
    void failedCreateQueuesNoInvitation() {
        when(userRepository.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateEmployeeRequest(
                "Someone", "taken@example.com", "Passw0rd1", "Sales", "Rep", LocalDate.of(2026, 1, 5), null, null)))
                .isInstanceOf(ConflictException.class);

        verify(notificationService, never()).enqueueEmployeeInvited(any(), anyBoolean());
    }

    @Test
    @DisplayName("resending an invitation asks for a resend and is audited either way")
    void resendInvitation() {
        User target = user(2L, Role.EMPLOYEE);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(notificationService.enqueueEmployeeInvited(target, true))
                .thenReturn(NotificationService.InvitationResult.ALREADY_QUEUED);

        EmployeeResponse response = service.resendInvitation(2L, new CurrentUser(1L, Role.ADMIN));

        assertThat(response.invitation()).isEqualTo("ALREADY_QUEUED");
        verify(auditService).record(eq(AuditActions.INVITATION_RESENT), eq("User"), eq(2L), eq(AuditOutcome.FAILURE), anyString());
    }

    @Test
    @DisplayName("a profile change tells the employee which fields changed; the acting administrator is passed along")
    void updateNotifiesAffectedEmployee() {
        User target = user(2L, Role.EMPLOYEE);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("user2@example.com", 2L)).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.update(2L, new UpdateEmployeeRequest("User 2", "user2@example.com", null, "Sales",
                "Developer", LocalDate.of(2024, 1, 15), Role.EMPLOYEE, EmploymentStatus.ACTIVE), new CurrentUser(1L, Role.ADMIN));

        verify(notificationService).enqueueEmployeeUpdated(eq(target), eq(List.of("department")), eq("user2@example.com"), eq(1L));
        verify(notificationService, never()).enqueueEmployeeStatusChanged(any(), anyBoolean(), anyLong());
        verify(notificationService, never()).enqueueSecurityNotice(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("deactivation and reactivation through update queue the status notices; a role change and a password reset queue security notices")
    void updateNotifiesStatusRoleAndPassword() {
        User target = user(2L, Role.EMPLOYEE);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("user2@example.com", 2L)).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(passwordEncoder.encode("Another-pass1")).thenReturn("another-hash");
        when(leaveRepository.findByEmployeeIdAndStatus(2L, LeaveStatus.PENDING)).thenReturn(List.of());

        service.update(2L, new UpdateEmployeeRequest("User 2", "user2@example.com", "Another-pass1", "Engineering",
                "Developer", LocalDate.of(2024, 1, 15), Role.ADMIN, EmploymentStatus.INACTIVE), new CurrentUser(1L, Role.ADMIN));
        verify(notificationService).enqueueEmployeeStatusChanged(target, false, 1L);
        verify(notificationService).enqueueSecurityNotice(eq(target), eq("role"), anyString(), anyString());
        verify(notificationService).enqueueSecurityNotice(eq(target), eq("reset"), anyString(), anyString());

        service.update(2L, new UpdateEmployeeRequest("User 2", "user2@example.com", null, "Engineering",
                "Developer", LocalDate.of(2024, 1, 15), Role.ADMIN, EmploymentStatus.ACTIVE), new CurrentUser(1L, Role.ADMIN));
        verify(notificationService).enqueueEmployeeStatusChanged(target, true, 1L);
    }

    @Test
    @DisplayName("deactivating queues the status notice; changing your own password queues a security notice without the password")
    void deactivateAndPasswordChangeNotify() {
        User target = user(2L, Role.EMPLOYEE);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(leaveRepository.findByEmployeeIdAndStatus(2L, LeaveStatus.PENDING)).thenReturn(List.of());
        service.deactivate(2L, new CurrentUser(1L, Role.ADMIN));
        verify(notificationService).enqueueEmployeeStatusChanged(target, false, 1L);

        User owner = user(3L, Role.EMPLOYEE);
        when(userRepository.findById(3L)).thenReturn(Optional.of(owner));
        when(passwordEncoder.matches("Passw0rd1", owner.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode("Brand-new-pass2")).thenReturn("new-hash");
        service.changePassword(3L, new com.worktrack.dto.ChangePasswordRequest("Passw0rd1", "Brand-new-pass2"));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(notificationService).enqueueSecurityNotice(eq(owner), eq("selfpw"), anyString(), detail.capture());
        assertThat(detail.getValue()).doesNotContain("Brand-new-pass2").doesNotContain("new-hash");
    }
}
