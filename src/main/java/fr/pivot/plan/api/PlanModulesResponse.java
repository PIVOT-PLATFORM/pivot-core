package fr.pivot.plan.api;

import java.util.List;

/**
 * Response body of {@code PUT /api/superadmin/plans/{planId}/modules},
 * {@code POST /api/superadmin/plans/{planId}/modules/{moduleId}} and
 * {@code GET /api/superadmin/plans/{planId}/modules} — the current module list of a plan
 * (US03.3.1).
 *
 * @param moduleIds current, exact module ids bundled in the plan, sorted alphabetically
 */
public record PlanModulesResponse(List<String> moduleIds) {
}
