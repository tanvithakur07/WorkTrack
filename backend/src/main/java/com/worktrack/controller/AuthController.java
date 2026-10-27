package com.worktrack.controller;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.worktrack.dto.ChangePasswordRequest;
import com.worktrack.dto.EmployeeResponse;
import com.worktrack.dto.LoginRequest;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.exception.IncorrectPasswordException;
import com.worktrack.exception.TooManyRequestsException;
import com.worktrack.security.CustomUserDetailsService;
import com.worktrack.security.LoginAttemptLimiter;
import com.worktrack.security.WorkTrackUserDetails;
import com.worktrack.service.AuditActions;
import com.worktrack.service.AuditService;
import com.worktrack.service.EmployeeService;

/**
 * Login, current user, password change. Logout (POST /api/auth/logout) is handled by Spring
 * Security's logout filter, configured in SecurityConfig, so it has no method here.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final EmployeeService employeeService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final CustomUserDetailsService userDetailsService;
    private final AuditService auditService;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          EmployeeService employeeService,
                          LoginAttemptLimiter loginAttemptLimiter,
                          CustomUserDetailsService userDetailsService,
                          AuditService auditService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.employeeService = employeeService;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.userDetailsService = userDetailsService;
        this.auditService = auditService;
    }

    /** No-op endpoint the front end can call to make sure the XSRF-TOKEN cookie exists before a POST. */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public EmployeeResponse login(@Valid @RequestBody LoginRequest body,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        // The client address as the servlet container sees it. Behind a reverse proxy set
        // server.forward-headers-strategy so this is the real client and not the proxy (see README).
        String clientIp = request.getRemoteAddr();

        // Throttled? Refuse BEFORE doing any password work. The answer is the same whether or not the
        // email exists, because the counter is keyed on whatever was typed.
        long retryAfter = loginAttemptLimiter.retryAfterSeconds(clientIp, email);
        if (retryAfter > 0) {
            auditService.recordAs(null, null, AuditService.safeLoginLabel(email), AuditActions.LOGIN_THROTTLED, "User",
                    null, AuditOutcome.DENIED, "sign-in refused by the attempt limiter");
            throw new TooManyRequestsException(
                    "Too many sign-in attempts. Please wait a few minutes and try again", retryAfter);
        }

        Authentication authentication;
        try {
            // Throws AuthenticationException (-> 401) for unknown email, wrong password or inactive account.
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, body.password()));
        } catch (AuthenticationException failure) {
            loginAttemptLimiter.recordFailure(clientIp, email);
            // No password is ever passed to the audit service; the typed e-mail is kept only if it looks like one.
            auditService.recordAs(null, null, AuditService.safeLoginLabel(email), AuditActions.LOGIN_FAILURE, "User",
                    null, AuditOutcome.FAILURE, "invalid credentials or inactive account");
            throw failure;
        }
        loginAttemptLimiter.recordSuccess(clientIp, email);

        // Defend against session fixation: a session that existed before login gets a new id.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }

        // Since Spring Security 6 the context must be saved explicitly when authenticating by hand.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        WorkTrackUserDetails principal = (WorkTrackUserDetails) authentication.getPrincipal();
        auditService.recordAs(principal.getId(), principal.getRole(), principal.getUsername(), AuditActions.LOGIN_SUCCESS,
                "User", principal.getId(), AuditOutcome.SUCCESS, "signed in");
        return employeeService.getById(principal.getId(), principal.toCurrentUser());
    }

    @GetMapping("/me")
    public EmployeeResponse me(@AuthenticationPrincipal WorkTrackUserDetails principal) {
        return employeeService.getById(principal.getId(), principal.toCurrentUser());
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal WorkTrackUserDetails principal,
                                               @Valid @RequestBody ChangePasswordRequest body,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        // A hijacked session must not be usable to guess the current password at full speed, so wrong
        // guesses here share the sign-in throttle for this person and address.
        String clientIp = request.getRemoteAddr();
        long retryAfter = loginAttemptLimiter.retryAfterSeconds(clientIp, principal.getUsername());
        if (retryAfter > 0) {
            throw new TooManyRequestsException(
                    "Too many attempts. Please wait a few minutes and try again", retryAfter);
        }
        try {
            employeeService.changePassword(principal.getId(), body);
        } catch (IncorrectPasswordException wrongCurrentPassword) {
            loginAttemptLimiter.recordFailure(clientIp, principal.getUsername());
            auditService.recordIndependent(AuditActions.PASSWORD_CHANGED, "User", principal.getId(),
                    AuditOutcome.FAILURE, "current password was wrong");
            throw wrongCurrentPassword;
        }
        loginAttemptLimiter.recordSuccess(clientIp, principal.getUsername());

        // Every OTHER session of this person stops working (ActiveUserFilter sees the new password
        // fingerprint). This session is refreshed with the new fingerprint and given a new id, so the
        // person who just changed their password stays signed in.
        WorkTrackUserDetails refreshed = (WorkTrackUserDetails) userDetailsService.loadUserByUsername(principal.getUsername());
        refreshed.eraseCredentials();   // the session never needs to hold the password hash
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                refreshed, null, refreshed.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        securityContextRepository.saveContext(context, request, response);
        return ResponseEntity.noContent().build();
    }
}
