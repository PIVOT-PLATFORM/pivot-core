package fr.pivot.modules.api;

/**
 * Levée quand un administrateur tente d'activer un module déjà activé pour son tenant.
 *
 * <p>Traduite en {@code 409 Conflict} par {@link AdminModuleController} — distincte du
 * comportement idempotent de {@link fr.pivot.core.modules.ModuleActivationService#activate}
 * (qui ne fait rien silencieusement) : l'API admin signale explicitement l'absence de
 * transition d'état pour permettre au frontend d'afficher un message adapté.
 */
public class ModuleAlreadyActiveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour un module déjà actif.
     *
     * @param moduleId identifiant du module déjà activé pour le tenant courant
     */
    public ModuleAlreadyActiveException(final String moduleId) {
        super("Module already active: " + moduleId);
    }
}
