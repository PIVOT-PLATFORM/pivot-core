package fr.pivot.core.auth;

/**
 * Minimal authenticated identity shared across {@code pivot-core} and satellite
 * {@code pivot-xxx-core} module repos (ADR-022, {@code pivot-core#171} EN17.1 volet
 * {@code fr.pivot.core.auth}).
 *
 * <p>Deliberately excludes every field that is specific to {@code pivot-core-app}'s own user
 * profile — email, password hash, 2FA/trusted-device state, locale, avatar, RGPD deletion status,
 * login history, etc. Those remain private to {@code fr.pivot.auth.entity.User} and are never
 * promoted into {@code pivot-core-starter}: a module repo evaluating whether a request is
 * authorized never needs more than "who is this, for which tenant, with which role."
 *
 * <p>Differs on purpose from {@link fr.pivot.core.tenant.TenantContext#userId()}, which is a
 * {@code String} inherited from a logging/display usage. {@code userId} here is the native
 * {@code Long} primary key ({@code public.users.id}), consistent with every other primary key in
 * the persistence layer ({@code User.id}, {@code Tenant.id}, {@code Team.id}) — required so a
 * future module repo can use it directly in a JPA/SQL join or filter, not only for logging.
 * {@link fr.pivot.core.tenant.TenantContext} itself is left unchanged; migrating its {@code
 * userId} type is out of scope for this record.
 *
 * @param userId   primary key of the authenticated user ({@code public.users.id})
 * @param tenantId primary key of the user's tenant ({@code public.tenants.id})
 * @param role     Spring Security role of the user (e.g. {@code ROLE_ADMIN})
 */
public record AuthenticatedPrincipal(Long userId, Long tenantId, String role) {
}
