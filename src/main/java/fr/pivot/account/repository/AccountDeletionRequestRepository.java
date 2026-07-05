package fr.pivot.account.repository;

import fr.pivot.account.entity.AccountDeletionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * JPA repository for {@link AccountDeletionRequest} (US02.2.4).
 */
public interface AccountDeletionRequestRepository extends JpaRepository<AccountDeletionRequest, Long> {

    /**
     * Resolves the cancellation link embedded in the deletion-confirmation email.
     *
     * @param cancelTokenHash SHA-256 hex-encoded hash of the raw cancellation token
     * @return the still-cancellable request, or empty if the hash is unknown or the request was
     *     already cancelled
     */
    Optional<AccountDeletionRequest> findByCancelTokenHashAndCancelledAtIsNull(String cancelTokenHash);

    /**
     * Latest deletion request for a user, cancelled or not — used by tests and diagnostics.
     *
     * @param userId the account owner
     * @return the most recent request, or empty if none was ever made
     */
    Optional<AccountDeletionRequest> findFirstByUserIdOrderByRequestedAtDesc(Long userId);
}
