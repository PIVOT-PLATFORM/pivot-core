package fr.pivot.core.auth;

import java.util.Optional;

/**
 * Resolves a raw opaque bearer token to a minimal {@link AuthenticatedPrincipal}, without
 * exposing the concrete {@code fr.pivot.auth.entity.User} JPA entity (ADR-022, {@code
 * pivot-core#171} EN17.1 volet {@code fr.pivot.core.auth}).
 *
 * <p>This is the shared extraction boundary: any consumer that only needs to know "who is this
 * bearer token" (not the full user profile) should depend on this interface rather than on {@code
 * fr.pivot.auth.service.TokenService}/{@code fr.pivot.auth.entity.User} directly.
 *
 * <p><strong>Current implementation.</strong> {@code fr.pivot.auth.service.TokenService}
 * (pivot-core-app) implements this interface — {@code resolve} delegates to its existing {@code
 * validate(String)} (unchanged: same DB lookup, same expiry/revocation/tenant-deactivation/
 * user-deactivation checks) and projects the result down to {@link AuthenticatedPrincipal}.
 * {@code TokenService} keeps its richer surface ({@code issue}, {@code rotate}, {@code revoke}…)
 * for {@code pivot-core-app}'s own needs, and {@code fr.pivot.config.TokenAuthenticationFilter}
 * is intentionally left untouched — it still populates {@code Authentication#getDetails()} with
 * the full {@code User} entity, which a number of existing controllers depend on.
 *
 * <p><strong>Not yet included.</strong> The validation logic itself (hash comparison, expiry,
 * {@code tenant_invalidation_timestamp}, {@code user.isActive()}) is not duplicated into {@code
 * pivot-core-starter} by this interface — per ADR-022, no {@code pivot-xxx-core} repo has a real
 * consumer for it yet. A future repo implementing this interface directly against {@code
 * public.access_tokens} (duplicated validation, not a network call to {@code pivot-core} — see
 * ADR-022) is expected to reuse this contract and {@link AuthenticatedPrincipal}'s shape, but the
 * query/validation logic itself is tracked as a separate, dedicated follow-up.
 */
@FunctionalInterface
public interface AuthenticatedPrincipalResolver {

    /**
     * Resolves a raw bearer token to the minimal identity of the request's caller.
     *
     * @param rawToken the raw opaque token extracted from the {@code Authorization: Bearer}
     *                 header, or {@code null}/blank
     * @return the resolved {@link AuthenticatedPrincipal}, or empty if the token is missing,
     *     invalid, expired, or otherwise rejected
     */
    Optional<AuthenticatedPrincipal> resolve(String rawToken);
}
