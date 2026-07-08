package fr.pivot.modules.api;

/**
 * DTO représentant un module PIVOT pour l'écran d'administration des modules.
 *
 * <p>Utilisé comme réponse de l'endpoint {@code GET /api/admin/modules}. Distinct de
 * {@link fr.pivot.modules.registry.ModuleDto} (contrat de {@code GET /api/modules}, orienté
 * utilisateur final avec {@code version}/{@code status}) : ce DTO est orienté administration
 * (activation/désactivation) et inclut une {@code description}.
 *
 * <p><strong>{@code description}</strong> provient de {@link fr.pivot.core.modules.PivotModule#getDescription()}
 * — jamais {@code null}, potentiellement vide si le module n'en fournit pas (voir la Javadoc de
 * {@code PivotModule} pour le contrat exact).
 *
 * <p><strong>{@code source} (US03.3.3) :</strong> seuls les modules visibles pour le tenant
 * apparaissent dans la liste — voir {@link AdminModuleListService} pour la résolution complète
 * de visibilité (filtrage par plan + overrides SUPER_ADMIN). Un module hors du plan du tenant
 * et sans override actif n'est jamais retourné (pas de {@code 403}, simple absence de la liste).
 *
 * @param id      identifiant technique unique du module (ex. {@code "whiteboard"})
 * @param name    nom affiché en UI
 * @param enabled {@code true} si le module est activé pour le tenant de l'administrateur courant
 * @param description description du module, alimentée par {@link fr.pivot.core.modules.PivotModule#getDescription()}
 * @param source  origine de la visibilité de ce module pour ce tenant : {@code "plan"} si le
 *     module est inclus dans le plan commercial souscrit par le tenant (ou si le tenant n'a
 *     encore aucun plan assigné — aucune restriction ne s'applique alors, voir
 *     {@link AdminModuleListService}) ; {@code "override"} si le module n'est <strong>pas</strong>
 *     inclus dans le plan mais est rendu visible uniquement par un override SUPER_ADMIN actif
 *     ({@code enabled = true}) — le frontend affiche alors un indicateur visuel distinct
 *     (« Activé par l'administrateur plateforme »)
 */
public record AdminModuleDto(String id, String name, boolean enabled, String description, String source) {
}
