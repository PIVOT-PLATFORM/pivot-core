package fr.pivot.modules.api;

import jakarta.validation.constraints.NotNull;

/**
 * Payload of {@code POST /api/superadmin/tenants/{tenantId}/modules/{moduleId}/override}
 * (US03.3.2 « SUPER_ADMIN active/désactive un module par tenant (override) »).
 *
 * <p>{@code enabled} is a boxed {@link Boolean} (not a primitive {@code boolean}) so that an
 * omitted field fails bean validation ({@code @NotNull}) rather than silently defaulting to
 * {@code false} — an override always carries an explicit, assumed value.
 *
 * @param enabled the value the super admin forces for this (tenant, module) couple
 */
public record SetModuleOverrideRequest(@NotNull Boolean enabled) {
}
