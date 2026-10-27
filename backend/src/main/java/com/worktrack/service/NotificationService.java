package com.worktrack.service;

import com.worktrack.dto.NotificationResponse;
import com.worktrack.dto.NotificationSummaryResponse;
import com.worktrack.dto.PageResponse;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.Notification;
import com.worktrack.entity.NotificationStatus;
import com.worktrack.entity.NotificationType;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;
import com.worktrack.exception.ForbiddenOperationException;
import com.worktrack.exception.ConflictException;
import com.worktrack.exception.ResourceNotFoundException;
import com.worktrack.mail.EmailTemplates;
import com.worktrack.repository.NotificationRepository;
import com.worktrack.repository.UserRepository;
import com.worktrack.security.CurrentUser;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional outbox. {@code enqueue*} is called from inside the leave transaction (it REQUIRES one,
 * so it cannot be called by accident outside it): the e-mail row is saved atomically with the leave change.
 * Nothing is sent here, and no SMTP problem can reach the leave transaction, because sending is done later
 * by {@link NotificationDispatcher} in its own transactions.
 *
 * What is NOT done here on purpose: a missing or malformed address does not fail the business change. It is
 * logged and no row is created (there is nobody to send to).
 *
 * Recipients follow least privilege: employees hear about things that concern them, administrators hear about
 * things that need or affect administration, and nobody is e-mailed about an action they performed themselves
 * (security notices excepted). The notification key includes the recipient, so one event with several
 * recipients is one row each and a repeated event is never queued twice.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    // Deliberately simple: one "@", no spaces, no line breaks (header injection), a dot in the domain.
    private static final Pattern SIMPLE_ADDRESS = Pattern.compile("^[^\\s@<>,;\"]+@[^\\s@<>,;\"]+\\.[^\\s@<>,;\"]+$");

    /** Placeholder inside invitation e-mails; the dispatcher swaps in a fresh one-time link when it SENDS. */
    public static final String SETUP_LINK_PLACEHOLDER = "{{SETUP_LINK}}";

    /** What happened when an invitation was requested. */
    public enum InvitationResult {
        QUEUED, ALREADY_QUEUED, SKIPPED_INACTIVE_ACCOUNT, SKIPPED_INVALID_EMAIL
    }

    private static final List<NotificationStatus> OPEN = List.of(NotificationStatus.PENDING, NotificationStatus.PROCESSING);

    private final NotificationRepository repository;
    private final UserRepository users;
    private final NotificationProperties properties;
    private final AuditService auditService;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, UserRepository users, NotificationProperties properties,
                               AuditService auditService, Clock clock) {
        this.repository = repository;
        this.users = users;
        this.properties = properties;
        this.auditService = auditService;
        this.clock = clock;
    }

    /* ------------------------------------------------------------------ leave */

    /**
     * A leave request was submitted: every ACTIVE administrator with a usable address is told (except the
     * submitter, if an administrator submitted it themselves). The employee is not e-mailed about their own action.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueLeaveSubmitted(LeaveRequest leave) {
        User employee = leave.getEmployee();
        List<User> admins = activeAdminsExcept(employee.getId());
        if (admins.isEmpty()) {
            log.warn("No administrator e-mail queued for leave request {}: there is no other active administrator with a valid address", leave.getId());
            return;
        }
        for (User admin : admins) {
            queueLeaveEmail(NotificationType.LEAVE_SUBMITTED, leave, admin);
        }
    }

    /** Approved or rejected, taken from the leave's current status. Goes to the employee the request belongs to. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueLeaveDecision(LeaveRequest leave) {
        switch (leave.getStatus()) {
            case APPROVED -> queueLeaveEmail(NotificationType.LEAVE_APPROVED, leave, leave.getEmployee());
            case REJECTED -> queueLeaveEmail(NotificationType.LEAVE_REJECTED, leave, leave.getEmployee());
            default -> throw new IllegalArgumentException("Not a decision: " + leave.getStatus());
        }
    }

    /**
     * A pending request was cancelled. Administrators are told (except the person who did it); the employee is told
     * only if somebody else cancelled it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueLeaveCancelled(LeaveRequest leave, Long actorUserId) {
        User employee = leave.getEmployee();
        for (User admin : activeAdminsExcept(actorUserId)) {
            if (!admin.getId().equals(employee.getId())) {
                queueLeaveEmail(NotificationType.LEAVE_CANCELLED, leave, admin);
            }
        }
        if (!employee.getId().equals(actorUserId)) {
            queueLeaveEmail(NotificationType.LEAVE_CANCELLED, leave, employee);
        }
    }

    private boolean queueLeaveEmail(NotificationType type, LeaveRequest leave, User recipient) {
        User employee = leave.getEmployee();
        boolean toOther = !recipient.getId().equals(employee.getId());
        EmailTemplates.Rendered rendered;
        try {
            rendered = EmailTemplates.render(type, new EmailTemplates.LeaveEmail(
                    properties.appName(),
                    employee.getFullName(),
                    leave.getLeaveType().name().charAt(0) + leave.getLeaveType().name().substring(1).toLowerCase() + " leave",
                    leave.getStartDate(), leave.getEndDate(), leave.getDays(), leave.getReason(),
                    leave.getReviewedBy() == null ? null : leave.getReviewedBy().getFullName(),
                    leave.getRejectionReason(),
                    toOther ? recipient.getFullName() : null,
                    toOther ? employeeCode(employee) : null,
                    properties.baseUrl() + "/leaves.html"));
        } catch (RuntimeException renderingProblem) {
            // a template problem must never undo a leave decision
            log.error("Could not render notification {} for leave request {}", type, leave.getId(), renderingProblem);
            return false;
        }
        String key = type.name() + ":" + leave.getId() + ":" + recipient.getId();
        return queue(type, key, recipient, leave.getId(), null, rendered);
    }

    /* ------------------------------------------------------- employee account */

    /**
     * Invitation: tells a new employee how to open their account. The e-mail carries a placeholder; the real
     * one-time link is created when the message is sent (see NotificationDispatcher), so no secret is stored here.
     * Called when an account is created and by the admin's "resend invitation" action.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public InvitationResult enqueueEmployeeInvited(User employee, boolean resend) {
        if (employee.getStatus() != EmploymentStatus.ACTIVE) {
            return InvitationResult.SKIPPED_INACTIVE_ACCOUNT;
        }
        if (!validAddress(employee.getEmail())) {
            log.warn("No invitation queued for user {}: the e-mail address is not valid", employee.getId());
            return InvitationResult.SKIPPED_INVALID_EMAIL;
        }
        if (repository.existsBySubjectUserIdAndTypeAndStatusIn(employee.getId(), NotificationType.EMPLOYEE_INVITED, OPEN)) {
            return InvitationResult.ALREADY_QUEUED; // a repeated click or retry cannot send two
        }
        String key = "EMPLOYEE_INVITED:" + employee.getId() + (resend ? ":" + clock.millis() : "");
        EmailTemplates.Rendered rendered = EmailTemplates.renderGeneral(new EmailTemplates.GeneralEmail(
                properties.appName(), employee.getFullName(),
                "Your " + properties.appName() + " account is ready",
                "Welcome to " + properties.appName(),
                "An administrator created a " + properties.appName() + " account for you. "
                        + properties.appName() + " is where you request leave and record your daily work.",
                List.of(new EmailTemplates.Row("Your sign-in e-mail", employee.getEmail()),
                        new EmailTemplates.Row("Sign-in page", properties.baseUrl() + "/login.html")),
                "Choose your password",
                SETUP_LINK_PLACEHOLDER,
                "Use the button to choose your own password. The link works once and expires after a few days; "
                        + "if it has expired, ask your administrator to send a new invitation. "
                        + "Nobody will ever e-mail you a password.",
                "#0f766e"));
        boolean queued = queue(NotificationType.EMPLOYEE_INVITED, key, employee, null, employee.getId(), rendered);
        return queued ? InvitationResult.QUEUED : InvitationResult.ALREADY_QUEUED;
    }

    /**
     * A profile change (name, department, job title, joining date, e-mail). The employee is told which fields changed
     * (names of fields, not values). If the e-mail address changed, the OLD address gets a security notice as well.
     * Not sent to the person who made the change themselves.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueEmployeeUpdated(User employee, List<String> changedFields, String previousEmail, Long actorUserId) {
        if (changedFields.isEmpty() || employee.getId().equals(actorUserId)) {
            return;
        }
        String stamp = String.valueOf(clock.millis());
        EmailTemplates.Rendered rendered = EmailTemplates.renderGeneral(new EmailTemplates.GeneralEmail(
                properties.appName(), employee.getFullName(), "Your " + properties.appName() + " profile was updated",
                "Your profile was updated",
                "An administrator changed details on your " + properties.appName() + " profile.",
                List.of(new EmailTemplates.Row("What changed", String.join(", ", changedFields))),
                "Open your profile", properties.baseUrl() + "/profile.html",
                "If something looks wrong, contact your administrator.", "#0f766e"));
        queue(NotificationType.EMPLOYEE_UPDATED, "EMPLOYEE_UPDATED:" + employee.getId() + ":" + stamp, employee, null, employee.getId(), rendered);
        if (previousEmail != null && !previousEmail.equalsIgnoreCase(employee.getEmail()) && validAddress(previousEmail)) {
            EmailTemplates.Rendered notice = EmailTemplates.renderGeneral(new EmailTemplates.GeneralEmail(
                    properties.appName(), employee.getFullName(), "The e-mail address of your " + properties.appName() + " account changed",
                    "Your sign-in e-mail was changed",
                    "The e-mail address of your " + properties.appName() + " account was changed by an administrator. "
                            + "Future messages go to the new address.",
                    List.of(), null, null,
                    "If you did not expect this, contact your administrator.", "#b45309"));
            queueTo(NotificationType.ACCOUNT_SECURITY, "ACCOUNT_SECURITY:" + employee.getId() + ":oldmail:" + stamp,
                    previousEmail, null, employee.getId(), notice);
        }
    }

    /**
     * Deactivation or reactivation: the affected employee (even though a deactivated account cannot sign in, the
     * address is still theirs) and every OTHER active administrator are told.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueEmployeeStatusChanged(User employee, boolean activated, Long actorUserId) {
        NotificationType type = activated ? NotificationType.EMPLOYEE_REACTIVATED : NotificationType.EMPLOYEE_DEACTIVATED;
        String stamp = String.valueOf(clock.millis());
        String verb = activated ? "reactivated" : "deactivated";
        if (!employee.getId().equals(actorUserId)) {
            EmailTemplates.Rendered own = EmailTemplates.renderGeneral(new EmailTemplates.GeneralEmail(
                    properties.appName(), employee.getFullName(),
                    "Your " + properties.appName() + " account was " + verb,
                    "Your account was " + verb,
                    activated
                            ? "An administrator reactivated your " + properties.appName() + " account. You can sign in again."
                            : "An administrator deactivated your " + properties.appName() + " account. You can no longer sign in, "
                                    + "and any leave request that was still waiting for a decision was cancelled.",
                    List.of(), activated ? "Sign in" : null, activated ? properties.baseUrl() + "/login.html" : null,
                    "If you have questions, contact your administrator.", activated ? "#15803d" : "#b91c1c"));
            queueTo(type, type.name() + ":" + employee.getId() + ":" + employee.getId() + ":" + stamp,
                    employee.getEmail(), null, employee.getId(), own);
        }
        for (User admin : activeAdminsExcept(actorUserId)) {
            if (admin.getId().equals(employee.getId())) {
                continue;
            }
            EmailTemplates.Rendered forAdmin = EmailTemplates.renderGeneral(new EmailTemplates.GeneralEmail(
                    properties.appName(), admin.getFullName(),
                    "An employee account was " + verb,
                    "An employee account was " + verb,
                    "An administrator " + verb + " an employee account.",
                    List.of(new EmailTemplates.Row("Employee", employee.getFullName() + " (" + employeeCode(employee) + ")")),
                    "Open employees", properties.baseUrl() + "/employees.html", null, "#475569"));
            queueTo(type, type.name() + ":" + employee.getId() + ":" + admin.getId() + ":" + stamp,
                    admin.getEmail(), null, employee.getId(), forAdmin);
        }
    }

    /**
     * An important security change (password changed or reset, role changed). Goes to the affected person only,
     * and never contains a password, hash or link.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueSecurityNotice(User employee, String kind, String headline, String detail) {
        EmailTemplates.Rendered rendered = EmailTemplates.renderGeneral(new EmailTemplates.GeneralEmail(
                properties.appName(), employee.getFullName(), "Security notice for your " + properties.appName() + " account",
                headline, detail, List.of(), null, null,
                "If you did not expect this, contact your administrator right away.", "#b45309"));
        queue(NotificationType.ACCOUNT_SECURITY, "ACCOUNT_SECURITY:" + employee.getId() + ":" + kind + ":" + clock.millis(),
                employee, null, employee.getId(), rendered);
    }

    /* ---------------------------------------------------------------- helpers */

    private boolean queue(NotificationType type, String key, User recipient, Long leaveId, Long subjectUserId,
                          EmailTemplates.Rendered rendered) {
        return queueTo(type, key, recipient.getEmail(), leaveId, subjectUserId, rendered);
    }

    private boolean queueTo(NotificationType type, String key, String address, Long leaveId, Long subjectUserId,
                            EmailTemplates.Rendered rendered) {
        String to = address == null ? "" : address.trim();
        if (!validAddress(to)) {
            log.warn("No {} e-mail queued: the recipient has no usable address", type);
            return false;
        }
        if (repository.existsByDedupeKey(key)) {
            return false; // already queued: queuing is idempotent
        }
        repository.save(new Notification(to, type, leaveId, key, rendered.subject(), rendered.html(), rendered.text(),
                clock.instant()).aboutUser(subjectUserId));
        return true;
    }

    private List<User> activeAdminsExcept(Long excludedUserId) {
        List<User> result = new ArrayList<>();
        for (User admin : users.findByStatusAndRoleOrderByFullNameAsc(EmploymentStatus.ACTIVE, Role.ADMIN)) {
            if (!admin.getId().equals(excludedUserId) && validAddress(admin.getEmail())) {
                result.add(admin);
            }
        }
        return result;
    }

    private static boolean validAddress(String address) {
        return address != null && SIMPLE_ADDRESS.matcher(address.trim()).matches();
    }

    private static String employeeCode(User user) {
        return String.format("EMP-%04d", user.getId());
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> search(NotificationStatus status, NotificationType type, Pageable pageable) {
        Specification<Notification> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }
        Page<NotificationResponse> page = repository.findAll(spec, pageable).map(NotificationResponse::from);
        return PageResponse.from(page);
    }

    @Transactional(readOnly = true)
    public NotificationSummaryResponse summary() {
        return new NotificationSummaryResponse(
                repository.countByStatus(NotificationStatus.PENDING),
                repository.countByStatus(NotificationStatus.PROCESSING),
                repository.countByStatus(NotificationStatus.SENT),
                repository.countByStatus(NotificationStatus.FAILED));
    }

    /** Re-queues a FAILED notification (administrators only; the URL rule and @PreAuthorize enforce that). */
    @Transactional
    public NotificationResponse retry(Long id, CurrentUser actor) {
        if (!actor.isAdmin()) {
            throw new ForbiddenOperationException("Only administrators can retry notifications");
        }
        Notification existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        int changed = repository.requeueFailed(id, clock.instant());
        if (changed == 0) {
            // its own transaction: the exception below rolls this one back, the audit record must survive
            auditService.recordIndependent("NOTIFICATION_RETRY", "Notification", id, AuditOutcome.FAILURE,
                    "not in FAILED state (" + existing.getStatus() + ")");
            throw new ConflictException("Only failed notifications can be retried (this one is " + existing.getStatus() + ")");
        }
        auditService.record("NOTIFICATION_RETRY", "Notification", id, AuditOutcome.SUCCESS,
                "type " + existing.getType() + ", leave request " + existing.getLeaveRequestId());
        return NotificationResponse.from(repository.findById(id).orElseThrow());
    }
}
