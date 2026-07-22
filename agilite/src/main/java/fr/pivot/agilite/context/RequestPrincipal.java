package fr.pivot.agilite.context;

/**
 * Represents the authenticated caller identity resolved from a validated bearer token.
 *
 * <p>Carries the real platform identities from {@code public.users}/{@code public.tenants}
 * ({@code BIGSERIAL}/{@code Long} — never {@code UUID}, there is no UUID identity concept
 * anywhere in {@code pivot-core}'s schema), resolved by {@link RequestPrincipalResolver} from
 * the {@code Authorization: Bearer} header via {@link fr.pivot.core.auth.AuthenticatedPrincipalResolver}
 * (EN08.3, ADR-022), replicating the pattern already in production in {@code
 * pivot-collaboratif-core}.
 *
 * <p>{@code role} (added US11.6.1, Sprint 21) carries the Spring Security role string (e.g.
 * {@code "ROLE_ADMIN"}) straight from {@link fr.pivot.core.auth.AuthenticatedPrincipal#role()} —
 * previously discarded here since no agilite feature needed a platform-role check before
 * {@code CapacityHolidayController}'s tenant-admin gate (US11.6.1), the first endpoint in this
 * module drawing a hard line between "any team member" and "tenant administrator" access.
 *
 * @param userId   the caller's {@code public.users.id}
 * @param tenantId the caller's {@code public.tenants.id}
 * @param role     the caller's Spring Security role (e.g. {@code "ROLE_ADMIN"})
 */
public record RequestPrincipal(Long userId, Long tenantId, String role) {}
