package fr.pivot.auth.repository;

import fr.pivot.auth.entity.EmailChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA repository for {@link EmailChangeRequest} — pending "change my email" confirmation
 * links (US02.2.2).
 */
public interface EmailChangeRequestRepository extends JpaRepository<EmailChangeRequest, Long> {

    Optional<EmailChangeRequest> findByTokenHash(String tokenHash);

    List<EmailChangeRequest> findAllByUserId(Long userId);

    /**
     * Marks every still-pending request for {@code userId} as cancelled — "a new request
     * supersedes the previous one" (AC). Direct UPDATE, bypasses the JPA first-level cache.
     *
     * @param userId      owner of the requests to cancel
     * @param cancelledAt timestamp to record
     */
    @Modifying
    @Query("UPDATE EmailChangeRequest e SET e.cancelledAt = :cancelledAt "
        + "WHERE e.user.id = :userId AND e.usedAt IS NULL AND e.cancelledAt IS NULL")
    void cancelPendingForUser(Long userId, Instant cancelledAt);

    /**
     * Atomically consumes a pending request — guards the race between the initial read and
     * this write (e.g. two near-simultaneous clicks on the same confirmation link).
     *
     * @param id     request id
     * @param usedAt timestamp to record
     * @return {@code 1} if this call won the race and consumed the row, {@code 0} if it was
     *     already terminal (used or cancelled) by the time this ran
     */
    @Modifying
    @Query("UPDATE EmailChangeRequest e SET e.usedAt = :usedAt "
        + "WHERE e.id = :id AND e.usedAt IS NULL AND e.cancelledAt IS NULL")
    int markUsed(Long id, Instant usedAt);

    /** Housekeeping — deletes rows past their TTL or already in a terminal state. */
    @Modifying
    @Query("DELETE FROM EmailChangeRequest e "
        + "WHERE e.expiresAt < :threshold OR e.usedAt IS NOT NULL OR e.cancelledAt IS NOT NULL")
    void deleteExpiredAndTerminal(Instant threshold);
}
