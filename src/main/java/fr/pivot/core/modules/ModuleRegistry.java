package fr.pivot.core.modules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registre central des modules PIVOT disponibles dans l'application.
 *
 * <p>Enregistrement par auto-découverte Spring : tous les beans {@link PivotModule}
 * présents dans le contexte (déclarés par pivot-core ou par un repo module externe via
 * {@code @Bean}/{@code @Component}) sont collectés à la construction — voir
 * {@link fr.pivot.core.modules.autoconfigure.PivotModulesAutoConfiguration}.
 *
 * <p>Le registre est immuable après construction : la découverte de modules est un
 * événement de démarrage d'application, pas un état runtime. L'activation par tenant
 * est portée séparément par {@link ModuleActivationService}.
 *
 * <p>Thread-safe : état interne immuable.
 */
public final class ModuleRegistry {

    private final Map<String, PivotModule> modulesById;

    /**
     * Construit le registre à partir des modules découverts dans le contexte Spring.
     *
     * <p>Fail-fast : deux modules déclarant le même identifiant est une erreur de
     * configuration — l'application ne doit pas démarrer.
     *
     * @param modules liste des beans {@link PivotModule} découverts (peut être vide)
     * @throws IllegalStateException si deux modules déclarent le même identifiant
     */
    public ModuleRegistry(final List<PivotModule> modules) {
        final Map<String, PivotModule> byId = new LinkedHashMap<>();
        for (final PivotModule module : modules) {
            final PivotModule previous = byId.putIfAbsent(module.getId(), module);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate PIVOT module id '" + module.getId() + "': "
                                + previous.getClass().getName() + " and " + module.getClass().getName());
            }
        }
        this.modulesById = Collections.unmodifiableMap(byId);
    }

    /**
     * Recherche un module par son identifiant technique.
     *
     * @param moduleId identifiant du module, ex. {@code "whiteboard"}
     * @return le module s'il est enregistré, {@link Optional#empty()} sinon
     */
    public Optional<PivotModule> findById(final String moduleId) {
        return Optional.ofNullable(modulesById.get(moduleId));
    }

    /**
     * Retourne la liste des modules disponibles, dans l'ordre de découverte.
     *
     * @return liste immuable, jamais {@code null} (vide si aucun module enregistré)
     */
    public List<PivotModule> getModules() {
        return List.copyOf(modulesById.values());
    }

    /**
     * Indique si un module est enregistré dans le registre.
     *
     * @param moduleId identifiant du module
     * @return {@code true} si un module porte cet identifiant
     */
    public boolean isRegistered(final String moduleId) {
        return modulesById.containsKey(moduleId);
    }

    /**
     * Nombre de modules enregistrés.
     *
     * @return taille du registre
     */
    public int count() {
        return modulesById.size();
    }
}
