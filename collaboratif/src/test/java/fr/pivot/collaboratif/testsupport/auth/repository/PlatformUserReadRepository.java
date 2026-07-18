package fr.pivot.collaboratif.testsupport.auth.repository;

import fr.pivot.collaboratif.testsupport.auth.entity.PlatformUser;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Read-only access to {@code public.users} — <strong>test-only</strong> (EN53.2 Vague 2).
 *
 * <p>See {@link fr.pivot.collaboratif.testsupport.auth.entity.PlatformUser}'s class Javadoc.
 * Extends Spring Data's bare {@link Repository} marker — not {@code JpaRepository} — so no
 * {@code save}/{@code delete} method is ever exposed: never writes to {@code users}.
 */
public interface PlatformUserReadRepository extends Repository<PlatformUser, Long> {

    /**
     * Finds a platform user by primary key.
     *
     * @param id the {@code public.users.id} to look up
     * @return the matching user, or empty if none is found
     */
    Optional<PlatformUser> findById(Long id);
}
