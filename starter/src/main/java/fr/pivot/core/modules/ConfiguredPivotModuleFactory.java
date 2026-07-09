package fr.pivot.core.modules;

import fr.pivot.core.modules.cache.ModuleActivationCacheService;

import java.util.List;

/**
 * Point d'entrée public pour construire des {@link ConfiguredPivotModule} depuis
 * {@link ModuleCatalogProperties} — {@link ConfiguredPivotModule} reste à visibilité paquet
 * (implémentation, pas contrat public), cette factory est le seul point d'accès pour
 * {@code fr.pivot.core.modules.autoconfigure} (paquet différent).
 */
public final class ConfiguredPivotModuleFactory {

    private ConfiguredPivotModuleFactory() {
    }

    /**
     * Construit un {@link PivotModule} par entrée du catalogue statique.
     *
     * @param catalogProperties            catalogue statique des modules réellement déployés
     * @param moduleActivationCacheService  cache-aside Redis (EN03.3) de résolution de
     *                                      l'activation par tenant, partagé par tous les modules
     *                                      construits (une seule instance)
     * @return liste immuable de modules, un par entrée du catalogue, dans l'ordre déclaré
     */
    public static List<PivotModule> fromCatalog(final ModuleCatalogProperties catalogProperties,
                                                  final ModuleActivationCacheService moduleActivationCacheService) {
        return catalogProperties.catalog().stream()
                .<PivotModule>map(entry -> new ConfiguredPivotModule(
                        entry.id(), entry.name(), entry.version(), entry.description(),
                        moduleActivationCacheService))
                .toList();
    }
}
