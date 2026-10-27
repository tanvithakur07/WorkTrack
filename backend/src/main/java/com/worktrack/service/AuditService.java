package com.worktrack.service;

import com.worktrack.dto.AuditEventResponse;
import com.worktrack.dto.PageResponse;
import com.worktrack.entity.AuditEvent;
import com.worktrack.entity.AuditOutcome;
import com.worktrack.entity.Role;
import com.worktrack.exception.BadRequestException;
import com.worktrack.repository.AuditEventRepository;
import com.worktrack.repository.AuditSpecifications;
import com.worktrack.security.WorkTrackUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes and reads the audit history.
 *
 * The ACTOR always comes from the server-side security context (or is passed in by server code, as for a
 * failed login), never from anything the browser sent. Two ways to record:
 *
 *  - {@link #record}: joins the caller's transaction. Used for business changes (employee created, leave
 *    approved ...). The audit row and the change commit together or not at all, so a change cannot happen
 *    without its audit line, and an audit line cannot describe a change that was rolled back.
 *    If the audit insert itself fails, the business change fails too: that is the intended trade-off.
 *  - {@link #recordIndependent}: its own transaction. Used where the surrounding transaction is about to
 *    roll back or there is none (failed logins, refused retries). It never throws: a problem is logged.
 *
 * Summaries are cut to 1000 characters and scrubbed of anything shaped like a secret. Callers must still
 * not pass secrets in; this is a safety net.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final Pattern SECRET_ASSIGNMENT =
            Pattern.compile("(?i)(password|passwd|pwd|secret|token|authorization|session|cookie)\\s*[=:]\\s*\\S+");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]+");
    static final int MAX_SUMMARY = 1000;

    private final AuditEventRepository repository;
    private final Clock clock;

    private final TransactionTemplate independent;

    public AuditService(AuditEventRepository repository, Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.independent = new TransactionTemplate(transactionManager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Joins the current transaction (starts one if there is none). Actor = the signed-in user. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String entityType, Long entityId, AuditOutcome outcome, String summary) {
        Actor actor = Actor.current();
        repository.save(build(actor.id(), actor.role(), actor.label(), action, entityType, entityId, outcome, summary));
    }

    /** Own transaction, never throws. Actor = the signed-in user, if any. */
    public void recordIndependent(String action, String entityType, Long entityId, AuditOutcome outcome, String summary) {
        Actor actor = Actor.current();
        recordAs(actor.id(), actor.role(), actor.label(), action, entityType, entityId, outcome, summary);
    }

    /**
     * Own transaction, never throws. For events where the server itself knows who acted (login, logout).
     * A TransactionTemplate is used instead of an annotation so the failure of the audit insert can be caught
     * HERE: with an annotation the commit failure would surface in the caller after this method returned.
     */
    public void recordAs(Long actorId, Role actorRole, String actorLabel, String action, String entityType,
                         Long entityId, AuditOutcome outcome, String summary) {
        try {
            AuditEvent event = build(actorId, actorRole, actorLabel, action, entityType, entityId, outcome, summary);
            independent.executeWithoutResult(status -> repository.saveAndFlush(event));
        } catch (RuntimeException problem) {
            // Not silent: it is logged at ERROR. It is not rethrown so a login or a refused request is not
            // turned into a server error just because the audit insert failed.
            log.error("AUDIT RECORD LOST for action {} (outcome {}): {}", action, outcome, problem.toString());
        }
    }

    private AuditEvent build(Long actorId, Role actorRole, String actorLabel, String action, String entityType,
                             Long entityId, AuditOutcome outcome, String summary) {
        return new AuditEvent(clock.instant(), actorId, actorRole == null ? null : actorRole.name(),
                cut(actorLabel, 254), action, entityType, entityId, outcome,
                cut(MDC.get("correlationId"), 64), clientIp(), clean(summary));
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> search(LocalDate from, LocalDate to, Long actorUserId, String action,
                                                   String entityType, Long entityId, AuditOutcome outcome,
                                                   Pageable pageable) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BadRequestException("The 'to' date cannot be before the 'from' date");
        }
        if (action != null && !action.isBlank() && !AuditActions.ALL.contains(action)) {
            throw new BadRequestException("Unknown action. Allowed values: " + String.join(", ", AuditActions.ALL));
        }
        Instant start = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Page<AuditEventResponse> page = repository
                .findAll(AuditSpecifications.filter(start, end, actorUserId, action, entityType, entityId, outcome), pageable)
                .map(AuditEventResponse::from);
        return PageResponse.from(page);
    }

    /** Removes line breaks and anything that looks like "password=...", then limits the length. */
    static String clean(String summary) {
        if (summary == null) {
            return null;
        }
        String result = SECRET_ASSIGNMENT.matcher(summary).replaceAll("$1=[redacted]");
        result = CONTROL.matcher(result).replaceAll(" ").trim();
        return cut(result, MAX_SUMMARY);
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** The e-mail typed in a failed login is kept only if it looks like an e-mail, so a mistyped password is not stored. */
    public static String safeLoginLabel(String typed) {
        if (typed == null) {
            return null;
        }
        String t = typed.trim();
        boolean plausible = t.length() <= 254 && t.matches("^[^\\s@]+@[^\\s@]+$");
        return plausible ? t : "[not an e-mail address]";
    }

    private static String clientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            HttpServletRequest request = servlet.getRequest();
            return cut(request.getRemoteAddr(), 45);
        }
        return null;
    }

    private record Actor(Long id, Role role, String label) {
        static Actor current() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof WorkTrackUserDetails user) {
                return new Actor(user.getId(), user.getRole(), user.getUsername());
            }
            return new Actor(null, null, null);
        }
    }
}
