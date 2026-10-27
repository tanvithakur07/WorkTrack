package com.worktrack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.worktrack.dto.AdminAnalyticsResponse;
import com.worktrack.dto.EmployeeAnalyticsResponse;
import com.worktrack.entity.DailyWorkLog;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.Role;
import com.worktrack.entity.WorkLogStatus;
import com.worktrack.exception.BadRequestException;
import com.worktrack.repository.DailyWorkLogRepository;
import com.worktrack.repository.LeaveRequestRepository;
import com.worktrack.repository.UserRepository;
import com.worktrack.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/** The database-heavy behaviour (GROUP BY queries) is covered by the integration tests; this checks the rules around it. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsServiceTest {

    // Thursday 2026-03-05; the previous Saturday/Sunday are 2026-02-28 and 2026-03-01
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-05T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private DailyWorkLogRepository workLogs;
    @Mock
    private UserRepository users;
    @Mock
    private LeaveRequestRepository leaves;

    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(workLogs, users, leaves, CLOCK);
        Page<DailyWorkLog> empty = new PageImpl<>(List.of());
        when(workLogs.findAll(any(Specification.class), any(Pageable.class))).thenReturn(empty);
        when(users.countByStatusAndRole(EmploymentStatus.ACTIVE, Role.EMPLOYEE)).thenReturn(4L);
    }

    @Test
    @DisplayName("without dates the admin trend covers exactly the last 30 days ending today, with zeros for quiet days")
    void defaultPeriodIsZeroFilled() {
        AdminAnalyticsResponse response = service.admin(null, null, null);

        assertThat(response.trend()).hasSize(30);
        assertThat(response.trend().get(29).date()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(response.trend().get(0).date()).isEqualTo(LocalDate.of(2026, 2, 4));
        assertThat(response.trend()).allSatisfy(point -> assertThat(point.hours()).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("weekends carry no coverage figure; a past weekday with no reports is 0 %")
    void coverageRules() {
        AdminAnalyticsResponse response = service.admin(LocalDate.of(2026, 2, 27), LocalDate.of(2026, 3, 5), null);

        var byDate = response.trend().stream().collect(java.util.stream.Collectors.toMap(AdminAnalyticsResponse.TrendPoint::date, p -> p));
        assertThat(byDate.get(LocalDate.of(2026, 2, 28)).coveragePercent()).isNull(); // Saturday
        assertThat(byDate.get(LocalDate.of(2026, 3, 1)).coveragePercent()).isNull();  // Sunday
        assertThat(byDate.get(LocalDate.of(2026, 2, 27)).coveragePercent()).isEqualTo(0.0); // Friday, nobody reported
        assertThat(response.averageCoveragePercent()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("with nobody expected to report there is no coverage at all, not a division by zero")
    void noEmployeesNoCoverage() {
        when(users.countByStatusAndRole(EmploymentStatus.ACTIVE, Role.EMPLOYEE)).thenReturn(0L);

        AdminAnalyticsResponse response = service.admin(null, null, null);

        assertThat(response.averageCoveragePercent()).isNull();
        assertThat(response.trend()).allSatisfy(point -> assertThat(point.coveragePercent()).isNull());
    }

    @Test
    @DisplayName("the period must not run backwards and may cover at most 366 days")
    void rangeValidation() {
        assertThatThrownBy(() -> service.admin(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 1), null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.admin(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 3, 5), null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.employee(new CurrentUser(1L, Role.EMPLOYEE), LocalDate.of(2025, 1, 1), LocalDate.of(2026, 3, 5)))
                .isInstanceOf(BadRequestException.class);
        // exactly 366 days is allowed
        assertThat(service.admin(LocalDate.of(2025, 3, 5), LocalDate.of(2026, 3, 5), null).trend()).hasSize(366);
    }

    @Test
    @DisplayName("the employee view asks the repository only about the caller's own id")
    void employeeScopedToCaller() {
        EmployeeAnalyticsResponse response = service.employee(new CurrentUser(9L, Role.EMPLOYEE), null, null);

        org.mockito.Mockito.verify(workLogs).submittedDayTotals(org.mockito.ArgumentMatchers.eq(9L), any(), any(), org.mockito.ArgumentMatchers.eq(WorkLogStatus.DRAFT));
        org.mockito.Mockito.verify(workLogs).statusCountsForEmployee(org.mockito.ArgumentMatchers.eq(9L), any(), any());
        assertThat(response.tasksByStatus()).containsKeys("DRAFT", "IN_PROGRESS", "COMPLETED", "BLOCKED");
    }
}
