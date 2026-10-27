package com.worktrack.util;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.worktrack.exception.BadRequestException;

/**
 * Builds a safe {@link Pageable} from raw query parameters. Sort fields come from a fixed
 * allow-list, so a client can never inject an arbitrary property name into a query.
 */
public final class PageRequests {

    public static final int MAX_PAGE_SIZE = 100;

    private PageRequests() {
    }

    public static Pageable of(int page, int size, String sortBy, String direction, Set<String> allowedSortFields) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (sortBy == null || !allowedSortFields.contains(sortBy)) {
            throw new BadRequestException("Unsupported sortBy field. Allowed values: "
                    + String.join(", ", new TreeSet<>(allowedSortFields)));
        }
        Sort.Direction sortDirection;
        String normalized = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("asc")) {
            sortDirection = Sort.Direction.ASC;
        } else if (normalized.equals("desc")) {
            sortDirection = Sort.Direction.DESC;
        } else {
            throw new BadRequestException("direction must be 'asc' or 'desc'");
        }

        Sort sort = Sort.by(sortDirection, sortBy);
        if (!"id".equals(sortBy)) {
            // Tie-breaker so pages are stable when many rows share the same sort value.
            sort = sort.and(Sort.by(Sort.Direction.ASC, "id"));
        }
        return PageRequest.of(page, size, sort);
    }
}
