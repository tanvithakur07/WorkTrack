package com.worktrack.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.worktrack.dto.AdminDashboardResponse;
import com.worktrack.dto.DepartmentCountResponse;
import com.worktrack.dto.EmployeeDashboardResponse;
import com.worktrack.dto.EmployeeResponse;
import com.worktrack.dto.LeaveResponse;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveStatus;
import com.worktrack.entity.User;
import com.worktrack.exception.ResourceNotFoundException;
import com.worktrack.repository.LeaveRequestRepository;
import com.worktrack.repository.UserRepository;
import com.worktrack.security.CurrentUser;
import com.worktrack.util.LeaveDayCalculator;

/** Every number on both dashboards is a database query; nothing is hard-coded. */
@Service
public class DashboardService {

    private final UserRepository userRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final Clock clock;

    public DashboardService(UserRepository userRepository,
                            LeaveRequestRepository leaveRequestRepository,
                            Clock clock) {
        this.userRepository = userRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse adminDashboard() {
        LocalDate today = LocalDate.now(clock);

        List<LeaveResponse> outToday = leaveRequestRepository.findOnLeaveOn(LeaveStatus.APPROVED, EmploymentStatus.ACTIVE, today)
                .stream().map(LeaveResponse::from).toList();
        List<LeaveResponse> pendingQueue = leaveRequestRepository.findTop5ByStatusOrderByCreatedAtDesc(LeaveStatus.PENDING)
                .stream().map(LeaveResponse::from).toList();
        List<DepartmentCountResponse> departments = userRepository.countByDepartment(EmploymentStatus.ACTIVE)
                .stream().map(row -> new DepartmentCountResponse(row.getDepartment(), row.getTotal())).toList();

        return new AdminDashboardResponse(
                userRepository.count(),
                userRepository.countByStatus(EmploymentStatus.ACTIVE),
                leaveRequestRepository.countByStatus(LeaveStatus.PENDING),
                leaveRequestRepository.countByStatus(LeaveStatus.APPROVED),
                leaveRequestRepository.countByStatus(LeaveStatus.REJECTED),
                leaveRequestRepository.countByStatus(LeaveStatus.CANCELLED),
                outToday.size(),
                departments,
                pendingQueue,
                outToday);
    }

    @Transactional(readOnly = true)
    public EmployeeDashboardResponse employeeDashboard(CurrentUser currentUser) {
        User user = userRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));
        Long id = user.getId();
        int year = LocalDate.now(clock).getYear();

        List<LeaveResponse> recent = leaveRequestRepository.findTop5ByEmployeeIdOrderByCreatedAtDesc(id)
                .stream().map(LeaveResponse::from).toList();

        return new EmployeeDashboardResponse(
                EmployeeResponse.from(user),
                leaveRequestRepository.countByEmployeeId(id),
                leaveRequestRepository.countByEmployeeIdAndStatus(id, LeaveStatus.PENDING),
                leaveRequestRepository.countByEmployeeIdAndStatus(id, LeaveStatus.APPROVED),
                leaveRequestRepository.countByEmployeeIdAndStatus(id, LeaveStatus.REJECTED),
                leaveRequestRepository.countByEmployeeIdAndStatus(id, LeaveStatus.CANCELLED),
                approvedWorkingDaysIn(id, year),
                recent);
    }

    /**
     * Approved working days that fall inside the calendar year. Requests that start in December and
     * end in January (or started last year) are clipped to the year, and weekends never count.
     */
    long approvedWorkingDaysIn(Long employeeId, int year) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        return leaveRequestRepository.findOverlapping(employeeId, LeaveStatus.APPROVED, yearStart, yearEnd)
                .stream()
                .mapToLong(leave -> LeaveDayCalculator.countWorkingDaysWithin(
                        leave.getStartDate(), leave.getEndDate(), yearStart, yearEnd))
                .sum();
    }
}
