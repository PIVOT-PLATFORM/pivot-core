package fr.pivot.modules.api;

import fr.pivot.tenant.entity.Tenant;

/**
 * Internal result of a {@link ModuleOverrideService} mutation — never serialized directly to
 * JSON (carries a JPA entity, see {@code Tenant}). {@link SuperAdminModuleOverrideController}
 * maps it into a {@link ModuleOverrideResponse} for the HTTP body, and uses {@link #tenant()}
 * to log the audit event against the correct tenant (US03.3.2 requires {@code superAdminId} —
 * the acting user, resolved separately by the controller from the security context — and the
 * target tenant to both be recorded).
 *
 * @param tenant     the tenant this override targets (already validated to exist)
 * @param moduleId   the module id this override targets
 * @param overridden {@code true} if an override is now active for this couple, {@code false} if
 *                   it was just removed (reverted to plan/tenant-admin behavior)
 * @param enabled    the resulting effective activation state for this couple
 *                   (see {@code ModuleActivationService#isEnabled})
 */
public record ModuleOverrideResult(Tenant tenant, String moduleId, boolean overridden, boolean enabled) {
}
