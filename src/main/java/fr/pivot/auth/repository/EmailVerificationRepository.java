package fr.pivot.auth.repository;

import fr.pivot.auth.entity.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Optional;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {
    Optional<EmailVerification> findByTokenHashAndUsedAtIsNull(String tokenHash);

    @Modifying
    @Query("DELETE FROM EmailVerification e WHERE e.expiresAt < :threshold OR e.usedAt IS NOT NULL")
    void deleteExpiredAndUsed(Instant threshold);
}
