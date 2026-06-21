package fr.pivot.auth.repository;

import fr.pivot.auth.entity.DeviceVerifyToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;

public interface DeviceVerifyTokenRepository extends JpaRepository<DeviceVerifyToken, Long> {

    Optional<DeviceVerifyToken> findByUserIdAndDeviceFingerprintAndConfirmedAtIsNull(Long userId, String deviceFingerprint);

    /** Finds a pending (not confirmed, not expired) token by device fingerprint only — used when userId not in context. */
    @Query("SELECT d FROM DeviceVerifyToken d WHERE d.deviceFingerprint = :fp AND d.confirmedAt IS NULL AND d.expiresAt > :now")
    Optional<DeviceVerifyToken> findPendingByFingerprint(@Param("fp") String fp, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM DeviceVerifyToken d WHERE d.expiresAt < :threshold")
    void deleteExpired(Instant threshold);
}
