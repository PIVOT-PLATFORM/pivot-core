package fr.pivot.account.repository;

import fr.pivot.account.entity.AccountDeletionOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * JPA repository for {@link AccountDeletionOtp} (US02.2.4).
 */
public interface AccountDeletionOtpRepository extends JpaRepository<AccountDeletionOtp, Long> {

    /**
     * Latest not-yet-confirmed OTP for a user — the only one eligible for verification.
     * Expiry is checked by the caller ({@code AccountDeletionOtp#isExpired()}), not here, so an
     * expired-but-still-latest row still surfaces a precise "expired" outcome instead of a
     * generic "no pending OTP".
     *
     * @param userId the account requesting deletion
     * @return the latest pending OTP, or empty if none was ever requested / all are confirmed
     */
    Optional<AccountDeletionOtp> findFirstByUserIdAndConfirmedAtIsNullOrderByCreatedAtDesc(Long userId);
}
