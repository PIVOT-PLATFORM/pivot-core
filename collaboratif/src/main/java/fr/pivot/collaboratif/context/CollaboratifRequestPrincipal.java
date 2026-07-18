package fr.pivot.collaboratif.context;

/**
 * Represents the authenticated caller identity resolved from a validated bearer token.
 *
 * <p>Carries the real platform identities from {@code public.users}/{@code public.tenants}
 * ({@code BIGSERIAL}/{@code Long} — never {@code UUID}, there is no UUID identity concept
 * anywhere in {@code pivot-core}'s schema), resolved by {@link CollaboratifRequestPrincipalResolver}
 * from the {@code Authorization: Bearer} header via {@link
 * fr.pivot.core.auth.AuthenticatedPrincipalResolver} (EN08.3, ADR-022). Replaces the previous
 * unauthenticated {@code X-Pivot-User-Id}/{@code X-Pivot-Tenant-Id} header mechanism.
 *
 * <p><strong>Named {@code CollaboratifRequestPrincipal}, not the bare {@code RequestPrincipal}</strong>
 * (EN53.2 Vague 2 modulith merge): the agilite module already owns a distinct {@code
 * fr.pivot.agilite.context.RequestPrincipal} record. Both classes are aggregated into the same
 * {@code pivot-core-app} classpath, and while two records with the same simple name in different
 * packages do not themselves collide at runtime, this module's own resolver is a {@code
 * @Component} bean whose default Spring bean name (derived from the simple class name) DOES
 * collide with agilite's equivalent bean unless renamed — see {@link
 * CollaboratifRequestPrincipalResolver}'s Javadoc. This record is renamed alongside it purely for
 * naming symmetry/clarity, not because the record itself would fail to compile or run unrenamed.
 *
 * @param userId   the caller's {@code public.users.id}
 * @param tenantId the caller's {@code public.tenants.id}
 */
public record CollaboratifRequestPrincipal(Long userId, Long tenantId) {}
