package fr.pivot.modules.api;

/**
 * Levée quand un administrateur tente d'activer/désactiver un module qui n'est pas
 * enregistré dans le {@link fr.pivot.core.modules.ModuleRegistry}.
 *
 * <p><strong>Simplification documentée :</strong> {@code activate}/{@code deactivate} ne
 * vérifient pas l'appartenance du module au plan commercial du tenant ({@code fr.pivot.plan}
 * — US03.3.1) — bloquer l'activation d'un module hors plan reste hors périmètre (voir
 * l'{@code @implNote} « future enforcement » de {@link fr.pivot.plan.entity.Plan}), distinct du
 * filtrage de <em>visibilité</em> de {@code GET .../modules} ({@link AdminModuleListService},
 * US03.3.3). En l'absence de cet enforcement sur activate/deactivate, « module non enregistré
 * dans le {@link fr.pivot.core.modules.ModuleRegistry} » reste traité comme équivalent à
 * « module non inclus dans le plan du tenant ». Cette exception encapsule
 * {@link fr.pivot.core.modules.UnknownModuleException} et est traduite en {@code 403 Forbidden}
 * par {@link AdminModuleController} (jamais 404, pour rester cohérent avec le contrat attendu
 * par le frontend admin sur ce flux spécifique).
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
