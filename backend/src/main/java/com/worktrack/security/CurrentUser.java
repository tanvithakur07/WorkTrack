package com.worktrack.security;

import com.worktrack.entity.Role;

/**
 * The minimum a service needs to know about whoever is calling. Services take this instead of
 * Spring Security types, which keeps them easy to unit-test.
 */
public record CurrentUser(Long id, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
