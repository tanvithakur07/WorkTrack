package com.worktrack.service;

import com.worktrack.entity.AccountToken;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.entity.EmploymentStatus;
import com.worktrack.entity.User;
import com.worktrack.exception.BadRequestException;
import com.worktrack.mail.EmailDeliveryException;
import com.worktrack.repository.AccountTokenRepository;
import com.worktrack.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitation links (first-login password setup).
 *
 * - The secret is 256 random bits, base64url. Only its SHA-256 hash is stored.
 * - The secret is placed after the "#" of the link, so browsers do not send it to the server in the request line
 *   or in Referer headers; the setup page reads it and POSTs it in the body.
 * - A link is single-use (atomic UPDATE), expires (worktrack.invitation.ttl-hours, default 72) and a newer link
 *   cancels the older ones.
 * - Links are created when the e-mail is SENT, not when it is queued, so no secret ever sits in the outbox table.
 */
@Service
public class AccountTokenService {

    static final String INVALID = "This link is invalid or has expired. Ask your administrator to send you a new invitation.";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountTokenRepository tokens;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final NotificationProperties properties;
    private final Clock clock;
    private final Duration ttl;

    public AccountTokenService(AccountTokenRepository tokens, UserRepository users, PasswordEncoder passwordEncoder,
                               AuditService auditService, NotificationProperties properties, Clock clock,
                               @Value("${worktrack.invitation.ttl-hours:72}") long ttlHours) {
        this.tokens = tokens;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
        this.ttl = Duration.ofHours(Math.max(1, ttlHours));
    }

    /** Creates a fresh invitation link for the person (older unused links stop working) and returns it. */
    @Transactional
    public String issueInvitationLink(Long userId) {
        User user = userId == null ? null : users.findById(userId).orElse(null);
        if (user == null || user.getStatus() != EmploymentStatus.ACTIVE) {
            // permanent: retrying will not bring the account back, so the e-mail is not sent
            throw new EmailDeliveryException("The account no longer exists or is not active", true, null);
        }
        Instant now = clock.instant();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.invalidateOpen(userId, AccountToken.INVITATION, now);
        tokens.save(new AccountToken(userId, sha256(secret), AccountToken.INVITATION, now, now.plus(ttl)));
        return properties.baseUrl() + "/setup-password.html#token=" + secret;
    }

    /**
     * Spends the link and sets the password. Every failure gives the same message so a guess reveals nothing.
     * The person's older sessions end because the password fingerprint changes (see ActiveUserFilter).
     */
    @Transactional
    public void completeSetup(String secret, String newPassword) {
        if (secret == null || secret.isBlank()) {
            throw new BadRequestException(INVALID);
        }
        String hash = sha256(secret.trim());
        if (tokens.consume(hash, clock.instant()) == 0) {
            throw new BadRequestException(INVALID);
        }
        AccountToken token = tokens.findByTokenHash(hash).orElseThrow(() -> new BadRequestException(INVALID));
        User user = users.findById(token.getUserId()).orElseThrow(() -> new BadRequestException(INVALID));
        if (user.getStatus() != EmploymentStatus.ACTIVE) {
            throw new BadRequestException(INVALID); // rolls the consume back; the account is not usable anyway
        }
        EmployeeService.requireEncodablePassword(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        users.saveAndFlush(user);
        auditService.record(AuditActions.PASSWORD_SETUP_COMPLETED, "User", user.getId(), AuditOutcome.SUCCESS,
                "password chosen through an invitation link"); // never the password or the link
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
