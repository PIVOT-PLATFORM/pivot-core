package fr.pivot.tenant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload of {@code POST /api/superadmin/tenants} — US06.2.1 « Super admin crée un tenant ».
 *
 * <p>Field names and casing follow the established convention of every other request DTO in
 * this codebase (e.g. {@code RegisterRequest}, {@code LoginRequest}): plain camelCase, no
 * snake_case override. This intentionally mirrors {@code name}/{@code plan}/{@code authMode}
 * as already exposed by the sibling {@code GET /api/superadmin/tenants} query parameters
 * (US06.2.3), except those are URL query parameters (snake_case is this app's query-param
 * convention) while this is a JSON body (camelCase is this app's JSON convention).
 *
 * <p>{@code slug} format is validated eagerly here ({@link TenantSlugPolicy#SLUG_REGEX}) so an
 * obviously malformed slug never reaches the service layer (400, not 422/409). The reserved-word
 * blocklist and the uniqueness check are business rules, not payload-shape rules — both are
 * evaluated in {@link SuperAdminTenantService#createTenant}.
 *
 * @param name     display name of the tenant — required
 * @param slug     unique, URL-safe identifier — required, {@code [a-z0-9-]\{3,50\}}
 * @param plan     subscription plan — required, one of {@code SAAS}, {@code ENTERPRISE}, {@code TRIAL}
 *                 (same set enforced by the {@code chk_tenants_plan} DB constraint)
 * @param authMode primary authentication method offered to the new tenant's users — required,
 *                 one of {@code LOCAL}, {@code OIDC}, {@code GOOGLE}
 */
public record CreateTenantRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Pattern(regexp = TenantSlugPolicy.SLUG_REGEX) String slug,
        @NotBlank @Pattern(regexp = "SAAS|ENTERPRISE|TRIAL") String plan,
        @NotBlank @Pattern(regexp = "LOCAL|OIDC|GOOGLE") String authMode) {
}
