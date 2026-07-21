package fr.pivot.collaboratif.whiteboard.member;

import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Read-only access to {@code public.users} for invitation-by-email resolution (US08.2.5).
 *
 * <p>Extends Spring Data's bare {@link Repository} marker — not {@code JpaRepository} — so no
 * {@code save}/{@code delete} method is ever exposed: this repository never writes to
 * {@code users}.
 */
public interface UserDirectoryRepository extends Repository<UserDirectoryEntry, Long> {

    /**
     * Resolves an active user by e-mail within a tenant (case-insensitive on e-mail).
     *
     * <p>Scoping by {@code tenantId} is a security requirement: an e-mail belonging to another
     * tenant, or to a deactivated account, must resolve to empty so the caller returns 404 —
     * no cross-tenant sharing and no cross-tenant e-mail enumeration.
     *
     * @param email    the invitee's e-mail address
     * @param tenantId the inviting caller's {@code public.tenants.id}
     * @return the matching active user, or empty if none
     */
    Optional<UserDirectoryEntry> findByEmailIgnoreCaseAndTenantIdAndActiveTrue(String email, Long tenantId);

    /**
     * Batch-loads directory entries by id within a tenant, for enriching a member list without an
     * N+1 query. A membership id absent from {@code public.users} (deleted/foreign-tenant account)
     * simply yields no row — the caller renders it as an unknown member.
     *
     * @param ids      the {@code public.users.id} values to resolve
     * @param tenantId the calling tenant's {@code public.tenants.id}
     * @return the matching entries (order unspecified; caller indexes by id)
     */
    List<UserDirectoryEntry> findAllByIdInAndTenantId(Collection<Long> ids, Long tenantId);
}
