package fr.pivot.plan.api;

/**
 * Levée quand un super admin tente de créer un plan avec un nom déjà utilisé par un autre plan.
 *
 * <p>Traduite en {@code 409 Conflict} par {@link SuperAdminPlanController}. Détectée via un
 * pré-check {@link fr.pivot.plan.repository.PlanRepository#findByName(String)} avant insertion —
 * la contrainte BDD {@code uq_plans_name} reste le filet de sécurité en cas de course, mais le
 * pré-check évite de dépendre systématiquement d'une {@code DataIntegrityViolationException}
 * pour ce cas d'usage courant (nom déjà pris).
 */
public class PlanNameAlreadyExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour un nom de plan déjà utilisé.
     *
     * @param name le nom en conflit
     */
    public PlanNameAlreadyExistsException(final String name) {
        super("Plan name already exists: " + name);
    }
}
