package fr.pivot.modules.api;

/**
 * DTO représentant un module PIVOT pour l'écran d'administration des modules.
 *
 * <p>Utilisé comme réponse de l'endpoint {@code GET /api/admin/modules}. Distinct de
 * {@link fr.pivot.modules.registry.ModuleDto} (contrat de {@code GET /api/modules}, orienté
 * utilisateur final avec {@code version}/{@code status}) : ce DTO est orienté administration
 * (activation/désactivation) et inclut une {@code description}.
 *
 * <p><strong>Limitation documentée :</strong> {@link fr.pivot.core.modules.PivotModule}
 * n'expose pas de méthode {@code getDescription()} — l'ajouter serait un changement du
 * contrat de module (hard block Gate 4, coordination obligatoire avec tous les repos
 * {@code pivot-xxx-core}). En attendant un mécanisme de métadonnées dédié, {@code description}
 * est toujours retournée vide.
 *
 * @param id      identifiant technique unique du module (ex. {@code "whiteboard"})
 * @param name    nom affiché en UI
 * @param enabled {@code true} si le module est activé pour le tenant de l'administrateur courant
 * @param description description du module — toujours vide pour le moment (voir limitation ci-dessus)
 */
public record AdminModuleDto(String id, String name, boolean enabled, String description) {
}
