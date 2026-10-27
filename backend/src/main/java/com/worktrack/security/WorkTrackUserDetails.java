package com.worktrack.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.Role;
import com.worktrack.entity.User;

/**
 * The authenticated principal stored in the server-side session. It is a small snapshot
 * (id, email, role) rather than the JPA entity, so nothing lazy or sensitive is kept in the session.
 * The password hash is wiped from it as soon as authentication succeeds.
 */
public class WorkTrackUserDetails implements UserDetails, CredentialsContainer {

    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String email;
    private final Role role;
    private final boolean enabled;
    private String passwordHash;
    /**
     * A fingerprint of the password hash the session was created with. ActiveUserFilter compares it with
     * the current one on every request, so changing or resetting a password ends all older sessions
     * (for example one opened on a stolen laptop). It is not reversible and holds no password data.
     */
    private final String credentialStamp;

    public WorkTrackUserDetails(Long id, String email, String passwordHash, Role role, boolean enabled) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
        this.credentialStamp = stampOf(passwordHash);
    }

    public static String stampOf(String passwordHash) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((passwordHash == null ? "" : passwordHash).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }

    public String getCredentialStamp() {
        return credentialStamp;
    }

    public static WorkTrackUserDetails from(User user) {
        return new WorkTrackUserDetails(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.getRole(),
                user.getStatus() == EmploymentStatus.ACTIVE);
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    public CurrentUser toCurrentUser() {
        return new CurrentUser(id, role);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}
