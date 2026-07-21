package fr.pivot.collaboratif.whiteboard.template;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link WhiteboardTemplate} entities.
 */
public interface WhiteboardTemplateRepository extends JpaRepository<WhiteboardTemplate, UUID> {

    /**
     * Returns all global public templates ({@code tenant_id IS NULL}), ordered for gallery
     * display.
     *
     * @return the ordered list of global templates
     */
    List<WhiteboardTemplate> findAllByTenantIdIsNullOrderByDisplayOrderAsc();

    /**
     * Finds a global public template by id.
     *
     * <p>Returns {@link Optional#empty()} both when no row exists for the id and when a row
     * exists but has a non-null {@code tenant_id} — a single outcome for both cases avoids
     * leaking the existence of a template that does not belong to the caller's scope.
     *
     * @param id the template UUID
     * @return an {@link Optional} containing the template, or empty if not found or not global
     */
    Optional<WhiteboardTemplate> findByIdAndTenantIdIsNull(UUID id);

    /**
     * Returns the caller's own templates, most recently edited first (US08.13.2).
     *
     * <p>Ordered by {@code updatedAt} rather than {@code createdAt}: a template the user has just
     * reworked through the draft cycle is the one they are most likely to reach for next.
     *
     * @param ownerId the owner's {@code public.users.id}
     * @return the owner's templates, newest edit first
     */
    List<WhiteboardTemplate> findAllByOwnerIdOrderByUpdatedAtDesc(Long ownerId);

    /**
     * Finds a template the caller owns.
     *
     * <p>The gate for every write operation on a template. Returns {@link Optional#empty()} both
     * when no row exists and when it exists but belongs to someone else — one outcome for both, so
     * the caller answers 404 without revealing that a template it may not touch exists.
     *
     * @param id      the template UUID
     * @param ownerId the caller's {@code public.users.id}
     * @return the template, or empty if absent or owned by another user
     */
    Optional<WhiteboardTemplate> findByIdAndOwnerId(UUID id, Long ownerId);

    /**
     * Finds a template the caller may instantiate — global, or one of their own.
     *
     * <p>Lifts the restriction that made a personal template unusable: resolution previously went
     * exclusively through {@link #findByIdAndTenantIdIsNull}, so a template captured by "save as
     * template" was faithfully stored and then replayable by nobody, including its author (gap
     * recorded in {@code WhiteboardTemplateService#createFromBoard}'s Javadoc).
     *
     * <p>The tenant guard on the owned branch is defence in depth: {@code ownerId} already implies
     * a single tenant, but pinning it here means a future cross-tenant user cannot widen the
     * lookup by changing nothing but their active tenant.
     *
     * @param id       the template UUID
     * @param ownerId  the caller's {@code public.users.id}
     * @param tenantId the caller's {@code public.tenants.id}
     * @return the template, or empty if it is neither global nor owned by the caller
     */
    @Query("""
            SELECT t FROM WhiteboardTemplate t
            WHERE t.id = :id
              AND (t.tenantId IS NULL
                   OR (t.ownerId = :ownerId AND t.tenantId = :tenantId))
            """)
    Optional<WhiteboardTemplate> findInstantiable(
            @Param("id") UUID id,
            @Param("ownerId") Long ownerId,
            @Param("tenantId") Long tenantId);
}
