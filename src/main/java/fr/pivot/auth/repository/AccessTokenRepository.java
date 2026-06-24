package fr.pivot.auth.repository;

import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.TokenStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link AccessToken} — DB-backed opaque session tokens.
 *
 * <p>Lookup is always by {@code token_hash} (SHA-256 of raw token).
 * The raw token is never stored.
 */
public interface AccessTokenRepository extends JpaRepository<AccessToken, Long> {

    /**
     * Finds an active (non-revoked) token by its hash.
     *
     * <p>Primary lookup path — called on every authenticated request by
     * {@code TokenAuthenticationFilter}.
     *
     * @param tokenHash SHA-256 hex-encoded hash of the raw bearer token
     * @param status    the token lifecycle status to filter on
     * @return matching token with the given status, or empty if not found
     */
    Optional<AccessToken> findByTokenHashAndStatus(String tokenHash, TokenStatus status);

    /**
     * Finds an active token with its user eagerly loaded — avoids N+1 on validate().
     */
    @Query("SELECT t FROM AccessToken t JOIN FETCH t.user WHERE t.tokenHash = :hash AND t.status = :status")
    Optional<AccessToken> findByTokenHashAndStatusWithUser(
        @Param("hash") String hash,
        @Param("status") TokenStatus status);

    /**
     * Acquires a pessimistic write lock on the token row only.
     *
     * <p>Intentionally does NOT JOIN FETCH the user: locking both {@code access_tokens}
     * and {@code users} in the same statement risks deadlocks when a concurrent thread
     * updates the user profile. The user is loaded lazily within the same transaction
     * by the caller (rotate()).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM AccessToken t WHERE t.tokenHash = :tokenHash AND t.status = :status")
    Optional<AccessToken> findByTokenHashAndStatusForUpdate(
        @Param("tokenHash") String tokenHash,
        @Param("status") TokenStatus status);

    /**
     * Revokes all active tokens for a user — called on password change and account lockout.
     *
     * @param userId  the user whose sessions must be invalidated
     * @param active  the active status value (passed as parameter to bypass JPQL literal)
     * @param revoked the revoked status value (passed as parameter to bypass JPQL literal)
     */
    @Modifying
    @Query("UPDATE AccessToken t SET t.status = :revoked, t.revokedAt = CURRENT_TIMESTAMP " +
           "WHERE t.user.id = :userId AND t.status = :active")
    void revokeAllForUser(
        @Param("userId") Long userId,
        @Param("active") TokenStatus active,
        @Param("revoked") TokenStatus revoked);

    /**
     * Purges expired and revoked tokens — intended for scheduled cleanup jobs.
     *
     * @param threshold tokens expired or revoked before this instant are deleted
     * @param expired   the expired status value
     * @param revoked   the revoked status value
     */
    @Modifying
    @Query("DELETE FROM AccessToken t WHERE " +
           "(t.expiresAt < :threshold AND t.status = :expired) OR " +
           "(t.status = :revoked AND t.revokedAt < :threshold)")
    void deleteExpiredAndRevoked(
        @Param("threshold") Instant threshold,
        @Param("expired") TokenStatus expired,
        @Param("revoked") TokenStatus revoked);

    /**
     * Updates {@code last_used_at} for a single token by id — used by the asynchronous,
     * throttled activity touch so a read-only validate() never opens a write transaction
     * on the request thread.
     *
     * @param id  the token primary key
     * @param now the timestamp to store
     */
    @Modifying
    @Query("UPDATE AccessToken t SET t.lastUsedAt = :now WHERE t.id = :id")
    void updateLastUsedAt(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Counts active sessions for a user — used to enforce MAX_SESSIONS_PER_USER.
     */
    long countByUserIdAndStatus(Long userId, TokenStatus status);

    /**
     * Deletes all tokens for a given user — used in integration test cleanup.
     */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM AccessToken t WHERE t.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    /**
     * Finds the oldest active session for a user — for FIFO eviction when MAX_SESSIONS is reached.
     * Sorted by createdAt ASC (oldest first = FIFO: first in, first out).
     */
    @Query("SELECT t FROM AccessToken t WHERE t.user.id = :userId AND t.status = :status ORDER BY t.createdAt ASC")
    List<AccessToken> findOldestActiveByUserId(
        @Param("userId") Long userId,
        @Param("status") TokenStatus status,
        Pageable pageable);
}
