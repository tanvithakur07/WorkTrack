package com.worktrack.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.worktrack.dto.LeaveCreateRequest;
import com.worktrack.dto.LeaveResponse;
import com.worktrack.dto.PageResponse;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveStatus;
import com.worktrack.entity.LeaveType;
import com.worktrack.entity.User;
import com.worktrack.exception.BadRequestException;
import com.worktrack.exception.ConflictException;
import com.worktrack.exception.ForbiddenOperationException;
import com.worktrack.exception.ResourceNotFoundException;
import com.worktrack.repository.LeaveRequestRepository;
import com.worktrack.repository.LeaveSpecifications;
import com.worktrack.repository.UserRepository;
import com.worktrack.security.CurrentUser;
import com.worktrack.util.LeaveDayCalculator;

/**
 * All leave rules live here, not in controllers.
 *
 * Policies (also documented in the README):
 * - Weekends are not counted as leave days; public holidays are not modelled.
 * - Start date may not be in the past, except SICK leave, which may be backdated up to 7 days.
 * - A request may span at most 60 calendar days and start at most 365 days from today.
 * - A new request may not overlap the employee's own PENDING or APPROVED requests.
 * - An approval is re-checked against APPROVED requests (another request may have been approved meanwhile).
 *
 * Concurrency: submit() and approve() first lock the EMPLOYEE's row (SELECT ... FOR UPDATE) and run
 * at READ_COMMITTED. Two simultaneous requests for the same person therefore queue up, and the second
 * one's overlap check reads what the first one committed. Without the lock both checks could pass
 * before either row existed (a check-then-insert race), and @Version cannot help because the two
 * requests are different rows. Locks are always taken in the same order, one row per transaction.
 */
@Service
public class LeaveService {

    static final int MAX_SICK_BACKDATE_DAYS = 7;
    static final int MAX_DURATION_DAYS = 60;
    static final int MAX_ADVANCE_DAYS = 365;

    private static final Long NO_EXCLUDED_ID = -1L;
    private static final List<LeaveStatus> BLOCKING_STATUSES = List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED);
    private static final List<LeaveStatus> APPROVED_ONLY = List.of(LeaveStatus.APPROVED);

    private final LeaveRequestRepository leaveRequestRepository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public LeaveService(LeaveRequestRepository leaveRequestRepository,
                        UserRepository userRepository,
                        Clock clock,
                        NotificationService notificationService,
                        AuditService auditService) {
        this.leaveRequestRepository = leaveRequestRepository;
        this.userRepository = userRepository;
        this.clock = clock;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LeaveResponse submit(CurrentUser currentUser, LeaveCreateRequest request) {
        // Lock first: nothing else is read before this, so every later read sees committed data.
        User employee = userRepository.findByIdForUpdate(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));
        if (employee.getStatus() != EmploymentStatus.ACTIVE) {
            throw new ForbiddenOperationException("Inactive accounts cannot submit leave requests");
        }

        validateDates(request.leaveType(), request.startDate(), request.endDate());

        int days = LeaveDayCalculator.countWorkingDays(request.startDate(), request.endDate());
        if (days == 0) {
            throw new BadRequestException("The selected dates are all on a weekend, so no leave days would be used");
        }
        if (leaveRequestRepository.existsOverlap(employee.getId(), BLOCKING_STATUSES,
                request.startDate(), request.endDate(), NO_EXCLUDED_ID)) {
            throw new ConflictException("You already have a pending or approved request that overlaps these dates");
        }

        LeaveRequest leave = new LeaveRequest(employee, request.leaveType(), request.startDate(),
                request.endDate(), days, request.reason().trim());
        LeaveRequest saved = leaveRequestRepository.save(leave);
        // Both rows below are saved in THIS transaction: the e-mail is queued (not sent) together with the
        // request, so neither can exist without the other. Sending happens later, outside this transaction.
        auditService.record(AuditActions.LEAVE_SUBMITTED, "LeaveRequest", saved.getId(), AuditOutcome.SUCCESS,
                saved.getLeaveType() + " leave " + saved.getStartDate() + " to " + saved.getEndDate() + " (" + days + " working days)");
        notificationService.enqueueLeaveSubmitted(saved);
        return LeaveResponse.from(saved);
    }

    /** Used for both "my requests" (employeeId = caller) and the admin list (employeeId optional). */
    @Transactional(readOnly = true)
    public PageResponse<LeaveResponse> search(Long employeeId, LeaveStatus status, LocalDate from, LocalDate to,
                                              Pageable pageable) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BadRequestException("The 'to' date cannot be before the 'from' date");
        }
        Page<LeaveResponse> page = leaveRequestRepository
                .findAll(LeaveSpecifications.filter(employeeId, status, from, to), pageable)
                .map(LeaveResponse::from);
        return PageResponse.from(page);
    }

    @Transactional(readOnly = true)
    public LeaveResponse getById(Long id, CurrentUser currentUser) {
        LeaveRequest leave = find(id);
        if (!currentUser.isAdmin() && !leave.getEmployee().getId().equals(currentUser.id())) {
            throw new ForbiddenOperationException("You can only view your own leave requests");
        }
        return LeaveResponse.from(leave);
    }

    @Transactional
    public LeaveResponse cancel(Long id, CurrentUser currentUser) {
        LeaveRequest leave = find(id);
        if (!leave.getEmployee().getId().equals(currentUser.id())) {
            throw new ForbiddenOperationException("You can only cancel your own leave requests");
        }
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new ConflictException("Only pending requests can be cancelled");
        }
        leave.cancel();
        LeaveRequest saved = leaveRequestRepository.saveAndFlush(leave);
        auditService.record(AuditActions.LEAVE_CANCELLED, "LeaveRequest", saved.getId(), AuditOutcome.SUCCESS,
                "cancelled by the employee while pending");
        notificationService.enqueueLeaveCancelled(saved, currentUser.id());
        return LeaveResponse.from(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LeaveResponse approve(Long id, CurrentUser currentUser) {
        User reviewer = requireAdmin(currentUser);
        // Lock the employee's row BEFORE loading the leave, so the status and the overlap check below
        // reflect everything that was committed while we waited for the lock.
        Long employeeId = leaveRequestRepository.findEmployeeIdById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found"));
        userRepository.findByIdForUpdate(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));
        LeaveRequest leave = find(id);
        assertReviewable(leave, reviewer);

        if (leaveRequestRepository.existsOverlap(leave.getEmployee().getId(), APPROVED_ONLY,
                leave.getStartDate(), leave.getEndDate(), leave.getId())) {
            throw new ConflictException("This request overlaps leave that is already approved for the employee");
        }

        leave.approve(reviewer, clock.instant());
        LeaveRequest saved = leaveRequestRepository.saveAndFlush(leave);
        auditService.record(AuditActions.LEAVE_APPROVED, "LeaveRequest", saved.getId(), AuditOutcome.SUCCESS,
                "approved " + saved.getStartDate() + " to " + saved.getEndDate() + " for employee " + saved.getEmployee().getId());
        notificationService.enqueueLeaveDecision(saved); // queued with the decision; an SMTP outage cannot undo it
        return LeaveResponse.from(saved);
    }

    @Transactional
    public LeaveResponse reject(Long id, String reason, CurrentUser currentUser) {
        User reviewer = requireAdmin(currentUser);
        LeaveRequest leave = find(id);
        assertReviewable(leave, reviewer);

        leave.reject(reviewer, clock.instant(), reason.trim());
        LeaveRequest saved = leaveRequestRepository.saveAndFlush(leave);
        auditService.record(AuditActions.LEAVE_REJECTED, "LeaveRequest", saved.getId(), AuditOutcome.SUCCESS,
                "rejected " + saved.getStartDate() + " to " + saved.getEndDate() + " for employee " + saved.getEmployee().getId()
                        + "; a reason was recorded");
        notificationService.enqueueLeaveDecision(saved);
        return LeaveResponse.from(saved);
    }

    private void validateDates(LeaveType type, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new BadRequestException("End date cannot be before start date");
        }
        LocalDate today = LocalDate.now(clock);
        if (start.isBefore(today)) {
            if (type != LeaveType.SICK) {
                throw new BadRequestException("Only sick leave can start in the past");
            }
            if (start.isBefore(today.minusDays(MAX_SICK_BACKDATE_DAYS))) {
                throw new BadRequestException(
                        "Sick leave can be backdated by at most " + MAX_SICK_BACKDATE_DAYS + " days");
            }
        }
        if (start.isAfter(today.plusDays(MAX_ADVANCE_DAYS))) {
            throw new BadRequestException("Leave cannot start more than " + MAX_ADVANCE_DAYS + " days from today");
        }
        long calendarDays = ChronoUnit.DAYS.between(start, end) + 1;
        if (calendarDays > MAX_DURATION_DAYS) {
            throw new BadRequestException("A single request can cover at most " + MAX_DURATION_DAYS + " calendar days");
        }
    }

    private void assertReviewable(LeaveRequest leave, User reviewer) {
        if (leave.getEmployee().getId().equals(reviewer.getId())) {
            throw new ForbiddenOperationException("You cannot approve or reject your own leave request");
        }
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new ConflictException("Only pending requests can be approved or rejected (this one is "
                    + leave.getStatus() + ")");
        }
    }

    private User requireAdmin(CurrentUser currentUser) {
        if (!currentUser.isAdmin()) {
            throw new ForbiddenOperationException("Only administrators can review leave requests");
        }
        return userRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Reviewer account not found"));
    }

    private LeaveRequest find(Long id) {
        return leaveRequestRepository.findDetailedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Leave request not found"));
    }
}
