package fr.pivot.tenant.api;

/**
 * Response body for {@code PATCH /api/superadmin/tenants/{tenantId}/status} (US06.2.2).
 *
 * @param tenantId identifier of the tenant whose status changed
 * @param status   the tenant's new status — currently always {@code "INACTIVE"}
 */
public record TenantStatusResponse(Long tenantId, String status) {}
