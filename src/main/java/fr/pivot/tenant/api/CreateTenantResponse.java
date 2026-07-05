package fr.pivot.tenant.api;

/**
 * Response body of {@code POST /api/superadmin/tenants} — US06.2.1.
 *
 * <p>{@code invitationUrl} is a first-admin onboarding link, not a per-invitation secure
 * token: PIVOT has no invitation-token entity yet (that is a distinct, not-yet-scheduled US —
 * inviting a specific person to a specific tenant). This link simply routes to the
 * tenant-scoped registration screen, reusing the same {@code pivot.app.url} + path convention
 * as {@link fr.pivot.auth.service.EmailService} (e.g. {@code appUrl + "/auth/verify-email?token=..."}).
 * See {@link SuperAdminTenantService#buildInvitationUrl} for the exact construction.
 *
 * @param id            technical identifier of the created tenant
 * @param slug          the tenant's slug, echoed back for convenience
 * @param invitationUrl onboarding URL for the tenant's first admin
 */
public record CreateTenantResponse(Long id, String slug, String invitationUrl) {
}
