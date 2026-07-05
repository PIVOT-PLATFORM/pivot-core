package fr.pivot.tenant.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for {@code PATCH /api/superadmin/tenants/{tenantId}/status} (US06.2.2
 * « Super admin désactive un tenant »).
 *
 * <p>Only {@code "INACTIVE"} is currently supported — {@link SuperAdminTenantService}
 * rejects any other value with {@code 400} via {@link UnsupportedTenantStatusException}.
 * The {@code tenantId} the status applies to is taken exclusively from the path variable,
 * never from this body.
 *
 * @param status the requested tenant status — must be {@code "INACTIVE"}
 */
public record TenantStatusRequest(@NotBlank String status) {

    /** The only status value currently accepted by this endpoint. */
    public static final String INACTIVE = "INACTIVE";
}
