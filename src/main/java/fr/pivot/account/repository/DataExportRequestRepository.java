package fr.pivot.account.repository;

import fr.pivot.account.entity.DataExportRequest;
import fr.pivot.account.entity.DataExportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link DataExportRequest} — RGPD Art. 20 export tracking (US02.3.1).
 */
public interface DataExportRequestRepository extends JpaRepository<DataExportRequest, Long> {

    /**
     * Finds the most recent export request for a user — the sole input to the
     * "already pending / less than 24h elapsed" rate-limit check.
     *
     * @param userId the export owner
     * @return the latest request by {@code requestedAt}, or empty if none exists
     */
    Optional<DataExportRequest> findFirstByUserIdOrderByRequestedAtDesc(Long userId);

    /**
     * Resolves a download token (already SHA-256 hashed by the caller) with its owning
     * user eagerly loaded — avoids a second query when checking ownership.
     *
     * @param tokenHash SHA-256 hex-encoded hash of the raw download token
     * @return the matching request, or empty if no {@code READY} request has this token
     */
    @Query("SELECT d FROM DataExportRequest d JOIN FETCH d.user WHERE d.tokenHash = :tokenHash")
    Optional<DataExportRequest> findByTokenHashWithUser(@Param("tokenHash") String tokenHash);

    /**
     * Finds requests whose download link has expired — feeds {@code ExportCleanupScheduler}.
     *
     * @param status the status to filter on (always {@code READY} in practice)
     * @param before the cutoff instant
     * @return expired requests, unordered
     */
    List<DataExportRequest> findByStatusAndExpiresAtBefore(DataExportStatus status, Instant before);

    /**
     * Deletes all export requests for a user — used in integration test cleanup.
     *
     * @param userId the owner whose requests must be purged
     */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM DataExportRequest d WHERE d.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
