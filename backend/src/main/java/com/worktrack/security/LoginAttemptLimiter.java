package com.worktrack.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Progressive throttling of failed sign-ins, kept in memory (one server). It uses only the JDK so it
 * is easy to read and to unit-test with a fixed {@link Clock}.
 *
 * Two independent counters are kept:
 * <ul>
 *   <li><b>IP + email</b>: after {@code maxFailuresPerAccount} wrong passwords inside the window, that
 *       pair is locked for {@code baseLock}, then 2x, 4x, 8x... up to {@code maxLock} for every further
 *       burst. Because the IP is part of the key, somebody typing a colleague's email from another
 *       machine cannot lock the colleague out; the real owner keeps working.</li>
 *   <li><b>IP alone</b>: a much higher limit that stops one machine from trying many different emails.</li>
 * </ul>
 * The key is built from whatever email was typed, whether or not such an account exists, so the
 * behaviour never reveals which emails are registered. A successful sign-in clears that pair's counter.
 * Locks and counters expire by themselves; nothing is ever written to the database.
 *
 * Limits of an in-memory design (documented in the README): counters reset on restart and are not
 * shared between several server instances. Use a shared store (or an API gateway) in that case.
 */
public final class LoginAttemptLimiter {

    /** Per-key state. Immutable: every update replaces the whole record inside ConcurrentHashMap.compute. */
    private record State(int failures, Instant windowStart, int lockouts, Instant lockedUntil, Instant lastSeen) {
    }

    private static final int MAX_TRACKED_KEYS = 20_000;

    private final Clock clock;
    private final int maxFailuresPerAccount;
    private final int maxFailuresPerIp;
    private final Duration window;
    private final Duration baseLock;
    private final Duration maxLock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public LoginAttemptLimiter(Clock clock, int maxFailuresPerAccount, int maxFailuresPerIp,
                               Duration window, Duration baseLock, Duration maxLock) {
        if (maxFailuresPerAccount < 1 || maxFailuresPerIp < 1) {
            throw new IllegalArgumentException("failure limits must be at least 1");
        }
        if (window.isNegative() || window.isZero() || baseLock.isNegative() || baseLock.isZero()
                || maxLock.compareTo(baseLock) < 0) {
            throw new IllegalArgumentException("window and base lock must be positive and maxLock >= baseLock");
        }
        this.clock = clock;
        this.maxFailuresPerAccount = maxFailuresPerAccount;
        this.maxFailuresPerIp = maxFailuresPerIp;
        this.window = window;
        this.baseLock = baseLock;
        this.maxLock = maxLock;
    }

    /** Seconds the caller must wait before trying again, or 0 when the attempt may go ahead. */
    public long retryAfterSeconds(String ip, String email) {
        Instant now = clock.instant();
        long wait = Math.max(remaining(accountKey(ip, email), now), remaining(ipKey(ip), now));
        return wait;
    }

    /** Call after a failed sign-in (wrong password, unknown email, inactive account). */
    public void recordFailure(String ip, String email) {
        Instant now = clock.instant();
        prune(now);
        register(accountKey(ip, email), maxFailuresPerAccount, now);
        register(ipKey(ip), maxFailuresPerIp, now);
    }

    /** Call after a successful sign-in: the person typed the right password, so forget their failures. */
    public void recordSuccess(String ip, String email) {
        states.remove(accountKey(ip, email));
    }

    /** Forgets everything. Used by tests so one test's failed logins cannot affect the next one. */
    public void reset() {
        states.clear();
    }

    int trackedKeys() {
        return states.size();
    }

    private long remaining(String key, Instant now) {
        State state = states.get(key);
        if (state == null || state.lockedUntil() == null || !state.lockedUntil().isAfter(now)) {
            return 0;
        }
        long millis = Duration.between(now, state.lockedUntil()).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

    private void register(String key, int limit, Instant now) {
        states.compute(key, (k, old) -> {
            State state = old;
            if (state != null && state.lockedUntil() != null && state.lockedUntil().isAfter(now)) {
                // Already locked: attempts during a lock are refused before they get here; keep the lock as is.
                return new State(state.failures(), state.windowStart(), state.lockouts(), state.lockedUntil(), now);
            }
            if (state == null || now.isAfter(state.windowStart().plus(window))) {
                // A quiet period passed. Forget old failures, and forget the lock history once it is
                // also older than the longest lock, so a legitimate user is not punished forever.
                int lockouts = state != null && now.isBefore(state.windowStart().plus(window).plus(maxLock))
                        ? state.lockouts() : 0;
                state = new State(0, now, lockouts, null, now);
            }
            int failures = state.failures() + 1;
            if (failures >= limit) {
                int lockouts = state.lockouts() + 1;
                Instant until = now.plus(lockDuration(lockouts));
                return new State(0, now, lockouts, until, now);
            }
            return new State(failures, state.windowStart(), state.lockouts(), null, now);
        });
    }

    private Duration lockDuration(int lockouts) {
        long factor = 1L << Math.min(lockouts - 1, 20);
        long millis = baseLock.toMillis() * factor;
        Duration candidate = Duration.ofMillis(Math.min(millis, maxLock.toMillis()));
        return candidate.compareTo(maxLock) > 0 ? maxLock : candidate;
    }

    /** Drops entries that can no longer matter, and caps memory if somebody floods with random keys. */
    private void prune(Instant now) {
        if (states.size() < 1_000) {
            return;
        }
        Duration keep = window.plus(maxLock);
        states.entrySet().removeIf(entry -> entry.getValue().lastSeen().plus(keep).isBefore(now)
                && (entry.getValue().lockedUntil() == null || !entry.getValue().lockedUntil().isAfter(now)));
        if (states.size() > MAX_TRACKED_KEYS) {
            // Still too many live keys: evict the least recently seen, never a key that is currently locked.
            states.entrySet().stream()
                    .filter(entry -> entry.getValue().lockedUntil() == null
                            || !entry.getValue().lockedUntil().isAfter(now))
                    .sorted((a, b) -> a.getValue().lastSeen().compareTo(b.getValue().lastSeen()))
                    .limit(states.size() - MAX_TRACKED_KEYS + MAX_TRACKED_KEYS / 10)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(states::remove);
        }
    }

    private static String ipKey(String ip) {
        return "ip:" + (ip == null ? "unknown" : ip);
    }

    /** The typed email is hashed so addresses are not kept in memory in readable form. */
    private static String accountKey(String ip, String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return "acct:" + (ip == null ? "unknown" : ip) + "|" + sha256(normalized);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}
