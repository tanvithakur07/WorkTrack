package com.worktrack.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.Nullable;

import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveStatus;

public interface LeaveRequestRepository
        extends JpaRepository<LeaveRequest, Long>, JpaSpecificationExecutor<LeaveRequest> {

    /** Overridden only to attach an entity graph, so one query loads employee and reviewer (no N+1). */
    @Override
    @EntityGraph(attributePaths = {"employee", "reviewedBy"})
    Page<LeaveRequest> findAll(@Nullable Specification<LeaveRequest> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"employee", "reviewedBy"})
    @Query("select l from LeaveRequest l where l.id = :id")
    java.util.Optional<LeaveRequest> findDetailedById(@Param("id") Long id);

    /** Just the owner's id, read without loading (or locking) the leave row itself. */
    @Query("select l.employee.id from LeaveRequest l where l.id = :id")
    java.util.Optional<Long> findEmployeeIdById(@Param("id") Long id);

    /**
     * True when the employee has a leave request in one of the given statuses whose date range
     * touches [start, end]. Two ranges overlap when each starts on or before the other ends.
     * Pass excludeId = -1 when there is nothing to exclude.
     */
    @Query("""
            select count(l) > 0 from LeaveRequest l
            where l.employee.id = :employeeId
              and l.status in :statuses
              and l.startDate <= :end
              and l.endDate >= :start
              and l.id <> :excludeId
            """)
    boolean existsOverlap(@Param("employeeId") Long employeeId,
                          @Param("statuses") Collection<LeaveStatus> statuses,
                          @Param("start") LocalDate start,
                          @Param("end") LocalDate end,
                          @Param("excludeId") Long excludeId);

    List<LeaveRequest> findByEmployeeIdAndStatus(Long employeeId, LeaveStatus status);

    long countByStatus(LeaveStatus status);

    long countByEmployeeId(Long employeeId);

    long countByEmployeeIdAndStatus(Long employeeId, LeaveStatus status);

    @EntityGraph(attributePaths = {"employee", "reviewedBy"})
    List<LeaveRequest> findTop5ByStatusOrderByCreatedAtDesc(LeaveStatus status);

    @EntityGraph(attributePaths = {"employee", "reviewedBy"})
    List<LeaveRequest> findTop5ByEmployeeIdOrderByCreatedAtDesc(Long employeeId);

    /**
     * Approved leave covering the given day, for people who are still ACTIVE. Deactivated employees
     * are left out of the "away today" view; their leave rows stay in the table as history.
     */
    @Query("""
            select l from LeaveRequest l join fetch l.employee e
            where l.status = :status
              and e.status = :employeeStatus
              and l.startDate <= :date and l.endDate >= :date
            order by e.fullName asc
            """)
    List<LeaveRequest> findOnLeaveOn(@Param("status") LeaveStatus status,
                                     @Param("employeeStatus") EmploymentStatus employeeStatus,
                                     @Param("date") LocalDate date);

    /**
     * Requests of one employee, in one status, whose date range touches [from, to]. Used for the
     * annual leave total: the service clips every range to the year and counts working days, which
     * cannot be done reliably in JPQL and is why this returns rows instead of a SUM.
     */
    @Query("""
            select l from LeaveRequest l
            where l.employee.id = :employeeId
              and l.status = :status
              and l.startDate <= :to
              and l.endDate >= :from
            """)
    List<LeaveRequest> findOverlapping(@Param("employeeId") Long employeeId,
                                       @Param("status") LeaveStatus status,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    /**
     * Leave rows with the given status whose range touches [from, to], for every employee. Used to
     * leave approved-leave days out of the missing-report check. Only ids and dates are read.
     */
    @Query("""
            select l from LeaveRequest l
            where l.status = :status and l.startDate <= :to and l.endDate >= :from
            """)
    List<LeaveRequest> findTouching(@Param("status") LeaveStatus status,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    /** The latest activity (created or decided), newest first, for the admin dashboard. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"employee", "reviewedBy"})
    List<LeaveRequest> findTop8ByOrderByUpdatedAtDesc();
}
