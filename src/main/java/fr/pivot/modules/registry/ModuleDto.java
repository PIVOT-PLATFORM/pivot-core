package fr.pivot.modules.registry;

/**
 * DTO représentant l'état d'un module PIVOT pour un tenant donné.
 *
 * <p>Utilisé comme réponse de l'endpoint {@code GET /api/modules}. Ne contient
 * aucune donnée interne de l'entité JPA — contrat API stable indépendant du modèle
 * de persistance.
 *
 * @param id      identifiant technique unique du module (ex. {@code "whiteboard"})
 * @param name    nom affiché en UI
 * @param version version du module
 * @param enabled {@code true} si le module est activé pour le tenant courant
 * @param status  statut opérationnel du module ({@link ModuleStatus})
 */
public record ModuleDto(
        String id,
        String name,
        String version,
        boolean enabled,
        ModuleStatus status) {
}
