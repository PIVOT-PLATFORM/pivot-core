package fr.pivot.agilite.testsupport.auth.repository;

import fr.pivot.agilite.testsupport.auth.entity.PlatformAccessToken;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Read-only access to {@code public.access_tokens} — <strong>test-only</strong> (EN53.1 Vague 1).
 *
 * <p>See {@link PlatformAccessToken}'s class Javadoc. Extends Spring Data's bare {@link
 * Repository} marker — not {@code JpaRepository} — so no {@code save}/{@code delete} method is
 * ever exposed: never writes to {@code access_tokens}.
 */
public interface AccessTokenReadRepository extends Repository<PlatformAccessToken, Long> {

    /**
     * Finds an access token by its SHA-256 hash and lifecycle status.
     *
     * @param tokenHash the SHA-256 hex-encoded hash of the raw bearer token
     * @param status    the lowercase lifecycle status to match (e.g. {@code "active"})
     * @return the matching token, or empty if none is found
     */
    Optional<PlatformAccessToken> findByTokenHashAndStatus(String tokenHash, String status);
}
