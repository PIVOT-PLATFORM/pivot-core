package fr.pivot.plan.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload of {@code POST /api/superadmin/plans} — US03.3.1 « SUPER_ADMIN définit modules
 * disponibles par plan » (AC-gap self-clarification: the literal AC only covers module-list
 * management on an existing plan; this endpoint creates the {@code Plan} row itself, without
 * which {@code planId} could never exist — see PR description).
 *
 * @param name display name of the plan — required, unique ({@code uq_plans_name})
 */
public record CreatePlanRequest(@NotBlank @Size(max = 100) String name) {
}
