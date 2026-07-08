package fr.pivot.core.modules;

/**
 * Levée quand une opération d'activation cible un module absent du {@link ModuleRegistry}.
 *
 * <p>Traduite en 404 côté API par le gestionnaire d'exceptions global — ne pas révéler
 * d'information sur les modules existants au-delà du strict nécessaire.
 */
public class UnknownModuleException extends RuntimeException {

    /**
     * Construit l'exception pour un identifiant de module inconnu.
     *
     * @param moduleId identifiant demandé, absent du registre
     */
    public UnknownModuleException(final String moduleId) {
        super("Unknown PIVOT module: " + moduleId);
    }
}
