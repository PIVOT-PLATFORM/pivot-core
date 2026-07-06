package fr.pivot.plan.api;

/**
 * Levée quand un super admin tente d'ajouter un {@code moduleId} non enregistré dans le
 * {@link fr.pivot.core.modules.ModuleRegistry} à un plan (ajout unitaire ou remplacement complet).
 *
 * <p>Traduite en {@code 400 Bad Request} par {@link SuperAdminPlanController} — c'est une
 * erreur d'entrée client (le module demandé n'existe pas dans le registre), distincte en
 * sémantique de {@link fr.pivot.modules.api.ModuleNotInPlanException} qui documente l'absence de
 * système de plan/entitlement pour l'activation tenant. Ici, à l'inverse, le système de plan
 * existe (c'est l'objet de cette US) — l'erreur porte uniquement sur un identifiant de module
 * qui n'est simplement pas connu du registre. Les deux exceptions ne doivent jamais être
 * réutilisées l'une pour l'autre.
 */
public class UnknownModuleIdException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour un identifiant de module inconnu du registre.
     *
     * @param moduleId identifiant de module demandé
     */
    public UnknownModuleIdException(final String moduleId) {
        super("Unknown module id: " + moduleId);
    }
}
