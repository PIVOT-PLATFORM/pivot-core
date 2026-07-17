package fr.pivot.collaboratif.testsupport.auth.repository;

import fr.pivot.collaboratif.testsupport.auth.entity.PlatformTenant;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Read-only access to {@code public.tenants} — <strong>test-only</strong> (EN53.2 Vague 2).
 *
 * <p>See {@link fr.pivot.collaboratif.testsupport.auth.entity.PlatformAccessToken}'s class
 * Javadoc. Extends Spring Data's bare {@link Repository} marker — not {@code JpaRepository} — so
 * no {@code save}/{@code delete} method is ever exposed: never writes to {@code tenants}.
 */
public interface PlatformTenantReadRepository extends Repository<PlatformTenant, Long> {

    /**
     * Finds a platform tenant by primary key.
     *
     * @param id the {@code public.tenants.id} to look up
     * @return the matching tenant, or empty if none is found
     */
    Optional<PlatformTenant> findById(Long id);
}
