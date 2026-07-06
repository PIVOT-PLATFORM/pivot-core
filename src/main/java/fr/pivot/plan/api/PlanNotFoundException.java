package fr.pivot.plan.api;

/**
 * Levée quand une opération super-admin cible un {@code planId} qui n'existe pas.
 *
 * <p>Traduite en {@code 404 Not Found} par {@link SuperAdminPlanController}.
 */
public class PlanNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour un plan introuvable.
     *
     * @param planId identifiant du plan recherché
     */
    public PlanNotFoundException(final Long planId) {
        super("Plan not found: " + planId);
    }
}
