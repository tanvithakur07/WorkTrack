package com.worktrack.service;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.worktrack.dto.ChangePasswordRequest;
import com.worktrack.dto.CreateEmployeeRequest;
import com.worktrack.dto.EmployeeResponse;
import com.worktrack.dto.PageResponse;
import com.worktrack.dto.UpdateEmployeeRequest;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveStatus;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;
import com.worktrack.exception.BadRequestException;
import com.worktrack.exception.ConflictException;
import com.worktrack.exception.ForbiddenOperationException;
import com.worktrack.exception.IncorrectPasswordException;
import com.worktrack.exception.ResourceNotFoundException;
import com.worktrack.repository.EmployeeSpecifications;
import com.worktrack.repository.LeaveRequestRepository;
import com.worktrack.repository.UserRepository;
import com.worktrack.security.CurrentUser;

@Service
public class EmployeeService {

    private final UserRepository userRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public EmployeeService(UserRepository userRepository,
                           LeaveRequestRepository leaveRequestRepository,
                           PasswordEncoder passwordEncoder,
                           AuditService auditService,
                           NotificationService notificationService) {
        this.userRepository = userRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional(readOnly = true)
    public PageResponse<EmployeeResponse> search(String text, String department, EmploymentStatus status,
                                                 Pageable pageable) {
        Page<EmployeeResponse> page = userRepository
                .findAll(EmployeeSpecifications.filter(text, department, status), pageable)
                .map(EmployeeResponse::from);
        return PageResponse.from(page);
    }

    /** Admins may view anyone; everyone else may only view themselves. */
    @Transactional(readOnly = true)
    public EmployeeResponse getById(Long id, CurrentUser currentUser) {
        if (!currentUser.isAdmin() && !currentUser.id().equals(id)) {
            throw new ForbiddenOperationException("You can only view your own profile");
        }
        return EmployeeResponse.from(findUser(id));
    }

    @Transactional
    public EmployeeResponse create(CreateEmployeeRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An employee with this email already exists");
        }

        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        requireEncodablePassword(request.password());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDepartment(request.department().trim());
        user.setJobTitle(request.jobTitle().trim());
        user.setJoiningDate(request.joiningDate());
        user.setRole(request.role() != null ? request.role() : Role.EMPLOYEE);
        user.setStatus(request.status() != null ? request.status() : EmploymentStatus.ACTIVE);

        User saved = userRepository.save(user);
        auditService.record(AuditActions.EMPLOYEE_CREATED, "User", saved.getId(), AuditOutcome.SUCCESS,
                "role " + saved.getRole() + ", status " + saved.getStatus() + ", department " + saved.getDepartment());
        // Same transaction: if creating the employee fails, nothing is queued; if it succeeds, exactly one invitation is.
        NotificationService.InvitationResult invitation = notificationService.enqueueEmployeeInvited(saved, false);
        return EmployeeResponse.from(saved).withInvitation(invitation.name());
    }

    /** Sends the invitation again (for example after the link expired). At most one may be waiting at a time. */
    @Transactional
    public EmployeeResponse resendInvitation(Long id, CurrentUser currentUser) {
        User user = findUser(id);
        NotificationService.InvitationResult result = notificationService.enqueueEmployeeInvited(user, true);
        auditService.record(AuditActions.INVITATION_RESENT, "User", user.getId(),
                result == NotificationService.InvitationResult.QUEUED ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                "invitation " + result);
        return EmployeeResponse.from(user).withInvitation(result.name());
    }

    @Transactional
    public EmployeeResponse update(Long id, UpdateEmployeeRequest request, CurrentUser currentUser) {
        User user = findUser(id);
        String email = normalizeEmail(request.email());

        if (userRepository.existsByEmailIgnoreCaseAndIdNot(email, id)) {
            throw new ConflictException("Another employee already uses this email");
        }
        // An admin must not be able to lock themselves out or remove the last way to administer the system.
        if (currentUser.id().equals(id)
                && (request.status() == EmploymentStatus.INACTIVE || request.role() != Role.ADMIN)) {
            throw new ConflictException("You cannot deactivate your own account or remove your own admin role");
        }

        boolean becomesInactive = user.getStatus() == EmploymentStatus.ACTIVE
                && request.status() == EmploymentStatus.INACTIVE;
        boolean becomesActive = user.getStatus() == EmploymentStatus.INACTIVE
                && request.status() == EmploymentStatus.ACTIVE;
        String previousEmail = user.getEmail();
        Role previousRole = user.getRole();
        // Field NAMES that change are audited, not their values (names and e-mails stay out of the history).
        List<String> changed = new java.util.ArrayList<>();
        List<String> profileFields = new java.util.ArrayList<>(); // plain wording for the employee's e-mail
        if (!user.getFullName().equals(request.fullName().trim())) {
            changed.add("fullName");
            profileFields.add("name");
        }
        if (!user.getEmail().equals(email)) {
            changed.add("email");
            profileFields.add("e-mail address");
        }
        if (!user.getDepartment().equals(request.department().trim())) {
            changed.add("department");
            profileFields.add("department");
        }
        if (!user.getJobTitle().equals(request.jobTitle().trim())) {
            changed.add("jobTitle");
            profileFields.add("job title");
        }
        if (!java.util.Objects.equals(user.getJoiningDate(), request.joiningDate())) {
            changed.add("joiningDate");
            profileFields.add("joining date");
        }
        if (user.getStatus() != request.status()) {
            changed.add("status " + user.getStatus() + " -> " + request.status());
        }
        boolean passwordReset = request.password() != null && !request.password().isBlank();

        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setDepartment(request.department().trim());
        user.setJobTitle(request.jobTitle().trim());
        user.setJoiningDate(request.joiningDate());
        user.setRole(request.role());
        user.setStatus(request.status());
        if (request.password() != null && !request.password().isBlank()) {
            requireEncodablePassword(request.password());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        if (becomesInactive) {
            cancelPendingLeaves(user.getId());
        }

        // saveAndFlush so the updatedAt timestamp is set before the response is built
        User saved = userRepository.saveAndFlush(user);
        auditService.record(AuditActions.EMPLOYEE_UPDATED, "User", saved.getId(), AuditOutcome.SUCCESS,
                changed.isEmpty() ? "no profile fields changed" : "changed: " + String.join(", ", changed));
        if (previousRole != saved.getRole()) {
            auditService.record(AuditActions.ROLE_CHANGED, "User", saved.getId(), AuditOutcome.SUCCESS,
                    "role " + previousRole + " -> " + saved.getRole());
        }
        if (becomesInactive) {
            auditService.record(AuditActions.EMPLOYEE_DEACTIVATED, "User", saved.getId(), AuditOutcome.SUCCESS,
                    "set to INACTIVE through an update; pending leave requests were cancelled");
        }
        if (passwordReset) {
            auditService.record(AuditActions.PASSWORD_CHANGED, "User", saved.getId(), AuditOutcome.SUCCESS,
                    "password reset by an administrator");
        }
        if (becomesActive) {
            auditService.record(AuditActions.EMPLOYEE_REACTIVATED, "User", saved.getId(), AuditOutcome.SUCCESS,
                    "set to ACTIVE through an update");
        }

        // E-mails (queued in this same transaction; never sent here). Nobody is told about their own action.
        Long actorId = currentUser.id();
        notificationService.enqueueEmployeeUpdated(saved, profileFields, previousEmail, actorId);
        if (becomesInactive) {
            notificationService.enqueueEmployeeStatusChanged(saved, false, actorId);
        } else if (becomesActive) {
            notificationService.enqueueEmployeeStatusChanged(saved, true, actorId);
        }
        if (previousRole != saved.getRole() && !saved.getId().equals(actorId)) {
            notificationService.enqueueSecurityNotice(saved, "role", "Your role was changed",
                    "An administrator changed your role from " + previousRole.name().toLowerCase(Locale.ROOT)
                            + " to " + saved.getRole().name().toLowerCase(Locale.ROOT) + ".");
        }
        if (passwordReset && !saved.getId().equals(actorId)) {
            notificationService.enqueueSecurityNotice(saved, "reset", "Your password was reset",
                    "An administrator reset the password of your account. Sign in with the password they gave you "
                            + "and change it from your profile as soon as you can.");
        }
        return EmployeeResponse.from(saved);
    }

    /**
     * "Delete" is a safe deactivation: the record and its leave history stay (so reports and
     * audit trails keep working), the person can no longer sign in, and their pending requests are
     * cancelled. Both changes happen in one transaction: either both are saved or neither is.
     */
    @Transactional
    public void deactivate(Long id, CurrentUser currentUser) {
        if (currentUser.id().equals(id)) {
            throw new ConflictException("You cannot deactivate your own account");
        }
        User user = findUser(id);
        if (user.getStatus() == EmploymentStatus.INACTIVE) {
            return;
        }
        user.setStatus(EmploymentStatus.INACTIVE);
        cancelPendingLeaves(user.getId());
        userRepository.saveAndFlush(user);
        auditService.record(AuditActions.EMPLOYEE_DEACTIVATED, "User", user.getId(), AuditOutcome.SUCCESS,
                "deactivated; pending leave requests were cancelled");
        notificationService.enqueueEmployeeStatusChanged(user, false, currentUser.id());
    }

    @Transactional(readOnly = true)
    public List<String> departments() {
        return userRepository.findDistinctDepartments();
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = findUser(userId);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new IncorrectPasswordException();
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new BadRequestException("New password must be different from the current password");
        }
        requireEncodablePassword(request.newPassword());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.saveAndFlush(user);
        auditService.record(AuditActions.PASSWORD_CHANGED, "User", userId, AuditOutcome.SUCCESS,
                "changed by the account owner"); // never the password itself
        notificationService.enqueueSecurityNotice(user, "selfpw", "Your password was changed",
                "The password of your account was just changed. If this was you, no action is needed.");
    }

    private void cancelPendingLeaves(Long employeeId) {
        List<LeaveRequest> pending = leaveRequestRepository.findByEmployeeIdAndStatus(employeeId, LeaveStatus.PENDING);
        pending.forEach(LeaveRequest::cancel);
        leaveRequestRepository.saveAll(pending);
    }

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));
    }

    /**
     * BCrypt only uses the first 72 BYTES of a password. The bean-validation rule counts characters, and
     * characters such as accented letters or emoji take 2-4 bytes, so a "72 character" password can be
     * longer than BCrypt accepts. Refuse it clearly (400) instead of failing with a 500.
     */
    static void requireEncodablePassword(String password) {
        if (password != null && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new BadRequestException(
                    "Password is too long: at most 72 bytes are supported (accented letters and emoji count as several)");
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
