package com.worktrack.repository;

import java.time.LocalDate;

import org.springframework.data.jpa.domain.Specification;

import com.worktrack.entity.LeaveRequest;
import com.worktrack.entity.LeaveStatus;

/** Optional filters for leave-request lists. Date filters match any request overlapping the range. */
public final class LeaveSpecifications {

    private LeaveSpecifications() {
    }

    public static Specification<LeaveRequest> filter(Long employeeId, LeaveStatus status,
                                                     LocalDate from, LocalDate to) {
        Specification<LeaveRequest> spec = (root, query, cb) -> cb.conjunction();
        if (employeeId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("employee").get("id"), employeeId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.<LocalDate>get("endDate"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.<LocalDate>get("startDate"), to));
        }
        return spec;
    }
}
