package com.worktrack.repository;

import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.User;

/**
 * Builds the employee search query from optional filters using the JPA Criteria API.
 * Values are always bound as parameters, never concatenated into SQL.
 */
public final class EmployeeSpecifications {

    private static final char LIKE_ESCAPE = '!';

    private EmployeeSpecifications() {
    }

    public static Specification<User> filter(String text, String department, EmploymentStatus status) {
        Specification<User> spec = (root, query, cb) -> cb.conjunction();
        if (StringUtils.hasText(text)) {
            spec = spec.and(nameOrEmailContains(text));
        }
        if (StringUtils.hasText(department)) {
            spec = spec.and(departmentEquals(department));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return spec;
    }

    private static Specification<User> nameOrEmailContains(String text) {
        String pattern = "%" + escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.<String>get("fullName")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.<String>get("email")), pattern, LIKE_ESCAPE));
    }

    private static Specification<User> departmentEquals(String department) {
        String value = department.trim().toLowerCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(cb.lower(root.<String>get("department")), value);
    }

    /** Makes %, _ and the escape character itself match literally inside a LIKE pattern. */
    static String escapeLike(String raw) {
        return raw.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
