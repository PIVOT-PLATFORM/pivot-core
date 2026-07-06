package fr.pivot.auth.repository;

import fr.pivot.auth.entity.SuspiciousLoginToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Optional;

/**
 * Repository for {@link SuspiciousLoginToken} — the "Not me" single-use link token
 * emailed on a passive suspicious-login alert (US01.4.3a).
 */
public interface SuspiciousLoginTokenRepository extends JpaRepository<SuspiciousLoginToken, Long> {

    Optional<SuspiciousLoginToken> findByTokenHashAndUsedAtIsNull(String tokenHash);

    /** Direct UPDATE — bypasses JPA first-level cache, guaranteed flush before SELECT. */
    @Modifying
    @Query("UPDATE SuspiciousLoginToken t SET t.usedAt = :usedAt WHERE t.id = :id AND t.usedAt IS NULL")
    int markUsed(Long id, Instant usedAt);

    @Modifying
    @Query("DELETE FROM SuspiciousLoginToken t WHERE t.expiresAt < :threshold")
    void deleteExpired(Instant threshold);
}
