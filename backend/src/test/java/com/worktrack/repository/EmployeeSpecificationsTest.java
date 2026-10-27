package com.worktrack.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmployeeSpecificationsTest {

    @Test
    @DisplayName("LIKE wildcards typed by the user are matched literally")
    void escapesLikeWildcards() {
        assertThat(EmployeeSpecifications.escapeLike("100%_!")).isEqualTo("100!%!_!!");
    }

    @Test
    @DisplayName("plain text is left unchanged")
    void plainTextUnchanged() {
        assertThat(EmployeeSpecifications.escapeLike("priya nair")).isEqualTo("priya nair");
    }
}
