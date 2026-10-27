package com.worktrack.repository;

import com.worktrack.entity.AccountToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {

    Optional<AccountToken> findByTokenHash(String tokenHash);

    /**
     * Uses the link: returns 1 only for the single caller that flips it from unused to used while it has not
     * expired. Two simultaneous submissions of the same link cannot both succeed.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AccountToken t set t.usedAt = :now where t.tokenHash = :hash and t.usedAt is null and t.expiresAt > :now")
    int consume(@Param("hash") String hash, @Param("now") Instant now);

    /** A new link replaces every older unused link of the same kind for that person. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AccountToken t set t.usedAt = :now where t.userId = :userId and t.purpose = :purpose and t.usedAt is null")
    int invalidateOpen(@Param("userId") Long userId, @Param("purpose") String purpose, @Param("now") Instant now);
}
