package com.worktrack.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.worktrack.exception.BadRequestException;

class PageRequestsTest {

    private static final Set<String> ALLOWED = Set.of("id", "fullName", "joiningDate");

    @Test
    @DisplayName("valid parameters build a page request with an id tie-breaker")
    void buildsPageRequest() {
        Pageable pageable = PageRequests.of(2, 25, "fullName", "DESC", ALLOWED);

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(25);
        assertThat(pageable.getSort().toList()).extracting(Sort.Order::getProperty)
                .containsExactly("fullName", "id");
        assertThat(pageable.getSort().getOrderFor("fullName").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("sorting by id alone does not add a second order")
    void idSortHasNoTieBreaker() {
        Pageable pageable = PageRequests.of(0, 10, "id", "asc", ALLOWED);
        assertThat(pageable.getSort().toList()).hasSize(1);
    }

    @Test
    @DisplayName("negative page numbers are rejected")
    void rejectsNegativePage() {
        assertThatThrownBy(() -> PageRequests.of(-1, 10, "id", "asc", ALLOWED))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("page size must be between 1 and 100")
    void rejectsBadSize() {
        assertThatThrownBy(() -> PageRequests.of(0, 0, "id", "asc", ALLOWED)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PageRequests.of(0, 101, "id", "asc", ALLOWED)).isInstanceOf(BadRequestException.class);
        assertThat(PageRequests.of(0, 100, "id", "asc", ALLOWED).getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("sort fields outside the allow-list are rejected (no property injection)")
    void rejectsUnknownSortField() {
        assertThatThrownBy(() -> PageRequests.of(0, 10, "passwordHash", "asc", ALLOWED))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unsupported sortBy");
        assertThatThrownBy(() -> PageRequests.of(0, 10, null, "asc", ALLOWED))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("direction must be asc or desc")
    void rejectsBadDirection() {
        assertThatThrownBy(() -> PageRequests.of(0, 10, "id", "sideways", ALLOWED))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PageRequests.of(0, 10, "id", null, ALLOWED))
                .isInstanceOf(BadRequestException.class);
    }
}
