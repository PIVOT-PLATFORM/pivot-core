package fr.pivot.auth.repository;

import fr.pivot.auth.entity.TrustedDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link TrustedDevice} rows.
 */
public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Long> {
    Optional<TrustedDevice> findByUserIdAndDeviceFingerprint(Long userId, String deviceFingerprint);

    @Modifying
    @Query("DELETE FROM TrustedDevice d WHERE d.expiresAt < :threshold")
    void deleteExpired(Instant threshold);

    /**
     * Lists a user's trusted devices, most recently active first — backs
     * {@code GET /api/auth/devices} (US01.4.2).
     *
     * @param userId the owning user
     * @return matching devices ordered by {@code lastSeenAt} descending
     */
    List<TrustedDevice> findByUserIdOrderByLastSeenAtDesc(Long userId);

    /**
     * Finds a trusted device by id scoped to its owning user — used to verify ownership before
     * revoking a specific device (US01.4.2). A device id that exists but belongs to another user
     * must yield an empty result here, never leak details about the other user's device.
     *
     * @param id     the device primary key (path variable, untrusted)
     * @param userId the current user's id (from the bearer token, never from the request)
     * @return the device if it exists and belongs to {@code userId}, empty otherwise
     */
    Optional<TrustedDevice> findByIdAndUserId(Long id, Long userId);
}
