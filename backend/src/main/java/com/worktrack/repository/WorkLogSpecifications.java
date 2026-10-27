package com.worktrack.repository;

import java.time.LocalDate;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import com.worktrack.entity.DailyWorkLog;
import com.worktrack.entity.WorkLogStatus;

/** Optional filters for work-log lists. Every value is bound as a parameter. */
public final class WorkLogSpecifications {

    private static final char LIKE_ESCAPE = '!';

    private WorkLogSpecifications() {
    }

    /**
     * @param ownerId when not null the list is limited to this employee (always the caller for "my" lists)
     * @param text    matches the task title or the employee's name, case-insensitive
     * @param includeDrafts false for the admin (team) list: drafts are private to their author
     */
    public static Specification<DailyWorkLog> filter(Long ownerId, WorkLogStatus status,
                                                     LocalDate from, LocalDate to, String text,
                                                     boolean includeDrafts) {
        Specification<DailyWorkLog> spec = (root, query, cb) -> cb.conjunction();
        if (!includeDrafts) {
            spec = spec.and((root, query, cb) -> cb.notEqual(root.get("status"), WorkLogStatus.DRAFT));
        }
        if (ownerId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("employee").get("id"), ownerId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.<LocalDate>get("workDate"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.<LocalDate>get("workDate"), to));
        }
        if (StringUtils.hasText(text)) {
            String pattern = "%" + EmployeeSpecifications.escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.<String>get("taskTitle")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("employee").<String>get("fullName")), pattern, LIKE_ESCAPE)));
        }
        return spec;
    }

    /** Employees of one department (case-insensitive, exact name). */
    public static Specification<DailyWorkLog> inDepartment(String department) {
        if (!StringUtils.hasText(department)) {
            return (root, query, cb) -> cb.conjunction();
        }
        String wanted = department.trim().toLowerCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(cb.lower(root.get("employee").<String>get("department")), wanted);
    }
}
