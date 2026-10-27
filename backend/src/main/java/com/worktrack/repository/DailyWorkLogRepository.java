package com.worktrack.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.Nullable;

import com.worktrack.entity.DailyWorkLog;
import com.worktrack.entity.WorkLogStatus;

public interface DailyWorkLogRepository
        extends JpaRepository<DailyWorkLog, Long>, JpaSpecificationExecutor<DailyWorkLog> {

    /** Overridden only to attach an entity graph, so one query loads the employee too (no N+1). */
    @Override
    @EntityGraph(attributePaths = {"employee"})
    Page<DailyWorkLog> findAll(@Nullable Specification<DailyWorkLog> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"employee"})
    @Query("select w from DailyWorkLog w where w.id = :id")
    Optional<DailyWorkLog> findDetailedById(@Param("id") Long id);

    /**
     * Hours already logged by the employee on a day (drafts included), ignoring one entry when editing
     * (pass -1 to ignore nothing). Returns null when there is nothing logged yet.
     */
    @Query("""
            select sum(w.hoursSpent) from DailyWorkLog w
            where w.employee.id = :employeeId and w.workDate = :date and w.id <> :excludeId
            """)
    BigDecimal sumHoursOnDay(@Param("employeeId") Long employeeId,
                             @Param("date") LocalDate date,
                             @Param("excludeId") Long excludeId);

    /** Submitted (non-draft) hours per day for one employee. */
    @Query("""
            select w.workDate as workDate, sum(w.hoursSpent) as hours, count(w) as entryCount
            from DailyWorkLog w
            where w.employee.id = :employeeId and w.status <> :draft
              and w.workDate between :from and :to
            group by w.workDate
            order by w.workDate
            """)
    List<DayRow> submittedDayTotals(@Param("employeeId") Long employeeId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to,
                                    @Param("draft") WorkLogStatus draft);

    /** Entry counts per status for one employee (drafts included). */
    @Query("""
            select w.status as status, count(w) as total from DailyWorkLog w
            where w.employee.id = :employeeId and w.workDate between :from and :to
            group by w.status
            """)
    List<StatusRow> statusCountsForEmployee(@Param("employeeId") Long employeeId,
                                            @Param("from") LocalDate from,
                                            @Param("to") LocalDate to);

    /** Submitted hours per employee for the admin summary; employeeId -1 means everyone (written -1L so the parameter is typed as a Long). */
    @Query("""
            select e.id as employeeId, e.fullName as employeeName, e.department as department,
                   sum(w.hoursSpent) as hours, count(w) as entryCount
            from DailyWorkLog w join w.employee e
            where w.status <> :draft and w.workDate between :from and :to
              and (:employeeId = -1L or e.id = :employeeId)
            group by e.id, e.fullName, e.department
            order by sum(w.hoursSpent) desc, e.fullName asc
            """)
    List<EmployeeRow> submittedTotalsByEmployee(@Param("employeeId") Long employeeId,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to,
                                                @Param("draft") WorkLogStatus draft);

    /** Entry counts per status for the admin summary (submitted entries only). */
    @Query("""
            select w.status as status, count(w) as total from DailyWorkLog w
            where w.status <> :draft and w.workDate between :from and :to
              and (:employeeId = -1L or w.employee.id = :employeeId)
            group by w.status
            """)
    List<StatusRow> submittedStatusCounts(@Param("employeeId") Long employeeId,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to,
                                          @Param("draft") WorkLogStatus draft);

    /** Which (employee, day) pairs have at least one submitted entry; used to find missing days. */
    @Query("""
            select distinct w.employee.id as employeeId, w.workDate as workDate from DailyWorkLog w
            where w.status <> :draft and w.workDate between :from and :to
            """)
    List<SubmittedDayRow> submittedDays(@Param("from") LocalDate from,
                                        @Param("to") LocalDate to,
                                        @Param("draft") WorkLogStatus draft);

    /** Submitted hours and entry counts per day, for everybody (the team trend; totals match the list). */
    @Query("""
            select w.workDate as workDate, sum(w.hoursSpent) as hours, count(w) as entryCount
            from DailyWorkLog w
            where w.status <> :draft and w.workDate between :from and :to
            group by w.workDate
            order by w.workDate
            """)
    List<DayRow> submittedTotalsByDay(@Param("from") LocalDate from,
                                      @Param("to") LocalDate to,
                                      @Param("draft") WorkLogStatus draft);

    /** How many distinct people of the given role and employment status submitted something each day. */
    @Query("""
            select w.workDate as workDate, count(distinct w.employee.id) as reporterCount
            from DailyWorkLog w
            where w.status <> :draft and w.workDate between :from and :to
              and w.employee.role = :role and w.employee.status = :employeeStatus
            group by w.workDate
            order by w.workDate
            """)
    List<ReporterRow> reportersByDay(@Param("from") LocalDate from,
                                     @Param("to") LocalDate to,
                                     @Param("draft") WorkLogStatus draft,
                                     @Param("role") com.worktrack.entity.Role role,
                                     @Param("employeeStatus") com.worktrack.entity.EmploymentStatus employeeStatus);

    interface ReporterRow {
        LocalDate getWorkDate();

        long getReporterCount();
    }

    interface DayRow {
        LocalDate getWorkDate();

        BigDecimal getHours();

        long getEntryCount();
    }

    interface StatusRow {
        WorkLogStatus getStatus();

        long getTotal();
    }

    interface EmployeeRow {
        Long getEmployeeId();

        String getEmployeeName();

        String getDepartment();

        BigDecimal getHours();

        long getEntryCount();
    }

    interface SubmittedDayRow {
        Long getEmployeeId();

        LocalDate getWorkDate();
    }
}
