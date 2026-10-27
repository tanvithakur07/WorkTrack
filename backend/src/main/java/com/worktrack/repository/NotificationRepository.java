package com.worktrack.repository;

import com.worktrack.entity.Notification;
import com.worktrack.entity.NotificationStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every state change is ONE guarded UPDATE whose WHERE clause names the state the row must still be in.
 * The database applies it atomically, so when two workers race for the same row exactly one UPDATE
 * reports "1 row changed" and the other reports 0. No application-level lock is needed.
 */
public interface NotificationRepository
        extends JpaRepository<Notification, Long>, JpaSpecificationExecutor<Notification> {

    boolean existsByDedupeKey(String dedupeKey);

    /** True while an e-mail of this kind about this person is still waiting or being sent. */
    boolean existsBySubjectUserIdAndTypeAndStatusIn(Long subjectUserId, com.worktrack.entity.NotificationType type,
                                                    java.util.Collection<NotificationStatus> statuses);

    /** Ids ready for an attempt: due PENDING rows, plus PROCESSING rows whose lease has expired (crashed worker). */
    @Query("""
            select n.id from Notification n
            where (n.status = com.worktrack.entity.NotificationStatus.PENDING and n.nextAttemptAt <= :now)
               or (n.status = com.worktrack.entity.NotificationStatus.PROCESSING and n.lockedUntil < :now)
            order by n.nextAttemptAt asc, n.id asc
            """)
    List<Long> findDueIds(@Param("now") Instant now, Pageable limit);

    /** Takes the row for sending. Returns 1 if this caller got it, 0 if someone else did or it is not due. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
            set n.status = com.worktrack.entity.NotificationStatus.PROCESSING,
                n.lockedUntil = :lockedUntil,
                n.lastAttemptAt = :now,
                n.attempts = n.attempts + 1
            where n.id = :id
              and ((n.status = com.worktrack.entity.NotificationStatus.PENDING and n.nextAttemptAt <= :now)
                or (n.status = com.worktrack.entity.NotificationStatus.PROCESSING and n.lockedUntil < :now))
            """)
    int claim(@Param("id") Long id, @Param("now") Instant now, @Param("lockedUntil") Instant lockedUntil);

    /** Only the worker that still holds the claim may record success. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
            set n.status = com.worktrack.entity.NotificationStatus.SENT,
                n.sentAt = :now, n.channel = :channel, n.lockedUntil = null, n.nextAttemptAt = null, n.lastError = null
            where n.id = :id and n.status = com.worktrack.entity.NotificationStatus.PROCESSING
            """)
    int markSent(@Param("id") Long id, @Param("now") Instant now, @Param("channel") String channel);

    /** Back to PENDING with a later due time (a temporary failure that will be retried). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
            set n.status = com.worktrack.entity.NotificationStatus.PENDING,
                n.nextAttemptAt = :next, n.lockedUntil = null, n.lastError = :error
            where n.id = :id and n.status = com.worktrack.entity.NotificationStatus.PROCESSING
            """)
    int markRetry(@Param("id") Long id, @Param("next") Instant next, @Param("error") String error);

    /** Gives up: a permanent error, or no attempts left. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
            set n.status = com.worktrack.entity.NotificationStatus.FAILED,
                n.nextAttemptAt = null, n.lockedUntil = null, n.lastError = :error
            where n.id = :id and n.status = com.worktrack.entity.NotificationStatus.PROCESSING
            """)
    int markFailed(@Param("id") Long id, @Param("error") String error);

    /** Administrator retry: only a FAILED row can be re-queued, and it starts again with a fresh attempt budget. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
            set n.status = com.worktrack.entity.NotificationStatus.PENDING,
                n.attempts = 0, n.nextAttemptAt = :now, n.lockedUntil = null
            where n.id = :id and n.status = com.worktrack.entity.NotificationStatus.FAILED
            """)
    int requeueFailed(@Param("id") Long id, @Param("now") Instant now);

    long countByStatus(NotificationStatus status);
}
