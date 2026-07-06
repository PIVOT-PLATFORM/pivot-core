package fr.pivot.modules.api;

/**
 * Response body of {@code POST}/{@code DELETE}
 * {@code /api/superadmin/tenants/{tenantId}/modules/{moduleId}/override} (US03.3.2).
 *
 * @param tenantId   the tenant this override targets
 * @param moduleId   the module id this override targets
 * @param overridden {@code true} if an override is now active for this couple (set by
 *                   {@code POST}), {@code false} if it was just removed (by {@code DELETE} —
 *                   the module reverted to plan/tenant-admin behavior)
 * @param enabled    the resulting effective activation state for this couple
 */
public record ModuleOverrideResponse(Long tenantId, String moduleId, boolean overridden, boolean enabled) {

    /**
     * Builds the response from an internal {@link ModuleOverrideResult}.
     *
     * @param result the service-layer result to project
     * @return the corresponding API response body
     */
    public static ModuleOverrideResponse from(final ModuleOverrideResult result) {
        return new ModuleOverrideResponse(
                result.tenant().getId(), result.moduleId(), result.overridden(), result.enabled());
    }
}
