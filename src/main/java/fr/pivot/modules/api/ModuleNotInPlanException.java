package fr.pivot.modules.api;

/**
 * Levée quand un administrateur tente d'activer/désactiver un module qui n'est pas
 * enregistré dans le {@link fr.pivot.core.modules.ModuleRegistry}.
 *
 * <p><strong>Simplification documentée :</strong> PIVOT ne dispose pas encore d'un système
 * de plan/entitlement par tenant (feature réservée à une phase ultérieure — voir backlog
 * pivot-docs). En l'absence de ce système, « module non enregistré dans le registre » est
 * traité comme équivalent à « module non inclus dans le plan du tenant ». Cette exception
 * encapsule {@link fr.pivot.core.modules.UnknownModuleException} et est traduite en
 * {@code 403 Forbidden} par {@link AdminModuleController} (jamais 404, pour rester cohérent
 * avec le contrat attendu par le frontend admin sur ce flux spécifique).
 */
public class ModuleNotInPlanException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception à partir de la cause technique (module inconnu du registre).
     *
     * @param moduleId identifiant du module demandé
     * @param cause    l'exception technique d'origine ({@link fr.pivot.core.modules.UnknownModuleException})
     */
    public ModuleNotInPlanException(final String moduleId, final Throwable cause) {
        super("Module not in tenant plan: " + moduleId, cause);
    }
}
