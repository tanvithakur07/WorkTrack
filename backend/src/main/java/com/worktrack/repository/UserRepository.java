package com.worktrack.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * Loads the row with SELECT ... FOR UPDATE. Leave submission and approval take this lock on the
     * EMPLOYEE's row first, so two requests for the same person run one after the other and the
     * overlap check always sees the other one's result. Only this one row is ever locked per
     * transaction, in the same order everywhere (user row, then leave rows), so it cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    boolean existsByRole(Role role);

    long countByStatus(EmploymentStatus status);

    long countByStatusAndRole(EmploymentStatus status, Role role);

    List<User> findByStatusAndRoleOrderByFullNameAsc(EmploymentStatus status, Role role);

    @Query("select distinct u.department from User u order by u.department")
    List<String> findDistinctDepartments();

    @Query("""
            select u.department as department, count(u) as total
            from User u
            where u.status = :status
            group by u.department
            order by count(u) desc, u.department asc
            """)
    List<DepartmentCount> countByDepartment(@Param("status") EmploymentStatus status);

    /** Projection for the department breakdown on the admin dashboard. */
    interface DepartmentCount {
        String getDepartment();

        long getTotal();
    }
}
