package fr.pivot.core.modules;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Catalogue statique des modules PIVOT réellement déployés dans cet environnement.
 *
 * <p>Complète l'auto-découverte de beans {@link PivotModule} (utile pour des modules qui
 * tourneraient réellement dans le même process que pivot-core, ex. en test) : un module métier
 * PIVOT ({@code pivot-collaboratif-core}, {@code pivot-agilite-core}, ...) tourne comme un
 * service Spring Boot **séparé** (process, port dédiés) et ne peut donc jamais s'enregistrer
 * comme bean dans le contexte Spring de pivot-core lui-même — l'auto-découverte seule laisse
 * {@link ModuleRegistry} vide en toutes circonstances réelles.
 *
 * <p>Chaque entrée du catalogue devient un {@link ConfiguredPivotModule} dont l'activation par
 * tenant est résolue via {@link fr.pivot.core.modules.cache.ModuleActivationCacheService}
 * (cache-aside Redis, EN03.3, devant {@link ModuleActivationService} et sa persistance
 * {@code module_activations}/{@code module_overrides}) — seule l'existence/l'identité du module
 * change de source, pas la logique d'activation.
 *
 * @param catalog liste des modules déployés (peut être vide — aucun module enregistré)
 */
@ConfigurationProperties(prefix = "pivot.modules")
public record ModuleCatalogProperties(List<CatalogEntry> catalog) {

    /**
     * Normalise {@code catalog} à une liste immuable non nulle.
     *
     * @param catalog liste brute liée depuis la configuration, peut être {@code null}
     */
    public ModuleCatalogProperties {
        catalog = catalog == null ? List.of() : List.copyOf(catalog);
    }

    /**
     * Une entrée du catalogue statique — identité d'un module réellement déployé.
     *
     * @param id          identifiant technique stable, ex. {@code "whiteboard"}
     * @param name        nom affiché en UI
     * @param version     version sémantique du module déployé
     * @param description description courte affichée en UI (carte module, écran
     *                    d'administration) — {@code null} normalisé en chaîne vide, voir
     *                    le constructeur canonique
     */
    public record CatalogEntry(String id, String name, String version, String description) {

        /**
         * Normalise {@code description} à une chaîne non nulle.
         *
         * @param id          identifiant technique stable
         * @param name        nom affiché en UI
         * @param version     version sémantique du module déployé
         * @param description description brute liée depuis la configuration, peut être
         *                    {@code null}
         */
        public CatalogEntry {
            description = description == null ? "" : description;
        }
    }
}
