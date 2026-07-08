package fr.pivot.core.modules;

import fr.pivot.core.tenant.TenantContext;

/**
 * Contrat de base de tout module PIVOT activable.
 *
 * <p>Chaque module déclare son identifiant, son nom et sa version, et expose sa
 * visibilité selon le contexte tenant courant. Les repos modules externes
 * ({@code pivot-pilotage-core}, {@code pivot-agilite-core}, {@code pivot-collaboratif-core})
 * implémentent cette interface et exposent leur implémentation comme bean Spring
 * ({@code @Bean} ou {@code @Component}) — le {@link ModuleRegistry} la découvre
 * automatiquement, sans modification de pivot-core.
 *
 * <p><strong>Contrat de module :</strong> tout changement de cette interface est un
 * hard block Gate 4 et exige la coordination pivot-core ↔ tous les repos modules.
 */
public interface PivotModule {

    /**
     * Identifiant technique unique du module.
     *
     * @return identifiant stable, ex. {@code "whiteboard"}, {@code "roadmap"}, {@code "quiz"}
     */
    String getId();

    /**
     * Nom du module affiché en UI.
     *
     * @return nom lisible, ex. {@code "Tableau blanc collaboratif"}
     */
    String getName();

    /**
     * Version du module.
     *
     * @return version sémantique, ex. {@code "1.0.0"}
     */
    String getVersion();

    /**
     * Description courte du module, affichée en UI (carte module, écran d'administration).
     *
     * @return description lisible, ex. {@code "Tableau blanc collaboratif temps réel"} —
     *     jamais {@code null}, chaîne vide acceptée si le module n'en fournit pas
     */
    String getDescription();

    /**
     * Indique si ce module est activé pour le tenant courant.
     *
     * <p>Module désactivé = 403 côté API + bundle non chargé côté Angular.
     *
     * @param ctx contexte tenant résolu depuis le token porteur
     * @return {@code true} si le module est activé pour ce tenant
     */
    boolean isEnabled(TenantContext ctx);
}
