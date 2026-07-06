package fr.pivot.plan.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Payload of {@code PUT /api/superadmin/plans/{planId}/modules} — full replacement of the
 * module list of a plan (US03.3.1).
 *
 * <p>{@code moduleIds} must not be {@code null}, but an explicit empty list is a valid, accepted
 * use case: it clears every module from the plan (not an error). Each element must be non-blank
 * — actual registration in {@link fr.pivot.core.modules.ModuleRegistry} is checked by
 * {@link PlanService}, not by bean validation, since it requires a runtime lookup.
 *
 * @param moduleIds the full, exact set of module ids the plan should contain after this call
 */
public record ReplacePlanModulesRequest(@NotNull List<@NotBlank String> moduleIds) {
}
