package fr.pivot.modules.registry;

/**
 * Contrat de base de tout module PIVOT activable.
 * Chaque module déclare son identifiant, son nom et sa version,
 * et expose sa visibilité selon le contexte tenant courant.
 */
public interface PivotModule {

    /** Identifiant technique unique : "whiteboard", "survey", "quiz"… */
    String getId();

    /** Nom affiché en UI. */
    String getName();

    String getVersion();

    /**
     * Indique si ce module est activé pour le tenant courant.
     * Module désactivé = 403 côté API + module non chargé côté Angular.
     */
    boolean isEnabled(TenantContext ctx);
}
