package fr.pivot.auth.repository;

import fr.pivot.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByTokenHashAndUsedAtIsNull(String tokenHash);

    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.expiresAt < :threshold")
    void deleteExpired(Instant threshold);
}
