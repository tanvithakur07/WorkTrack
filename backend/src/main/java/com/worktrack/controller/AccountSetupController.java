package com.worktrack.controller;

import com.worktrack.dto.SetupPasswordRequest;
import com.worktrack.exception.BadRequestException;
import com.worktrack.exception.TooManyRequestsException;
import com.worktrack.security.LoginAttemptLimiter;
import com.worktrack.service.AccountTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * First-login password setup from the invitation e-mail. Public (the person is not signed in yet), so it is
 * protected by: CSRF (like every POST), the one-time 256-bit secret, and the same per-address throttle that
 * protects the sign-in form. Wrong or expired links all get the same answer.
 */
@RestController
@RequestMapping("/api/auth")
public class AccountSetupController {

    private static final String THROTTLE_KEY = "setup-password";

    private final AccountTokenService accountTokenService;
    private final LoginAttemptLimiter limiter;

    public AccountSetupController(AccountTokenService accountTokenService, LoginAttemptLimiter limiter) {
        this.accountTokenService = accountTokenService;
        this.limiter = limiter;
    }

    @PostMapping("/setup-password")
    public ResponseEntity<Void> setupPassword(@Valid @RequestBody SetupPasswordRequest body, HttpServletRequest request) {
        String clientIp = request.getRemoteAddr();
        long retryAfter = limiter.retryAfterSeconds(clientIp, THROTTLE_KEY);
        if (retryAfter > 0) {
            throw new TooManyRequestsException("Too many attempts. Please wait a few minutes and try again", retryAfter);
        }
        try {
            accountTokenService.completeSetup(body.token(), body.password());
        } catch (BadRequestException invalidLink) {
            limiter.recordFailure(clientIp, THROTTLE_KEY);
            throw invalidLink;
        }
        limiter.recordSuccess(clientIp, THROTTLE_KEY);
        return ResponseEntity.noContent().build();
    }
}
