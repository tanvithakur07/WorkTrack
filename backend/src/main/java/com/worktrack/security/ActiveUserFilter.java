package com.worktrack.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.web.filter.OncePerRequestFilter;

import com.worktrack.entity.EmploymentStatus;
import com.worktrack.repository.UserRepository;

/**
 * A session stays valid until it expires, so on its own it would keep working after an admin
 * deactivates or demotes someone. For every API request this filter re-checks the account in the
 * database and ends the session if the account is gone, inactive, has a different role, or has had its
 * password changed or reset since the session was created.
 *
 * It is created with "new" in SecurityConfig (it is not a Spring bean), so it runs only inside
 * the security filter chain and not a second time in the servlet container.
 */
public class ActiveUserFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final AuthenticationEntryPoint entryPoint;

    public ActiveUserFilter(UserRepository userRepository, AuthenticationEntryPoint entryPoint) {
        this.userRepository = userRepository;
        this.entryPoint = entryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only API calls need the check; skipping static files avoids a database query per CSS/JS file.
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof WorkTrackUserDetails principal) {
            boolean stillValid = userRepository.findById(principal.getId())
                    .map(user -> user.getStatus() == EmploymentStatus.ACTIVE
                            && user.getRole() == principal.getRole()
                            && WorkTrackUserDetails.stampOf(user.getPasswordHash())
                                    .equals(principal.getCredentialStamp()))
                    .orElse(false);
            if (!stillValid) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                entryPoint.commence(request, response,
                        new SessionAuthenticationException("Session is no longer valid"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
