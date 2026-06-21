package fr.pivot.auth.repository;

import fr.pivot.auth.entity.TrustedDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Optional;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Long> {
    Optional<TrustedDevice> findByUserIdAndDeviceFingerprint(Long userId, String deviceFingerprint);

    @Modifying
    @Query("DELETE FROM TrustedDevice d WHERE d.expiresAt < :threshold")
    void deleteExpired(Instant threshold);
}
