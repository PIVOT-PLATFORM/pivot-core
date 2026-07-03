package fr.pivot.modules.registry;

/**
 * DTO représentant le statut d'activation d'un module PIVOT pour le tenant courant.
 *
 * <p>Utilisé comme réponse de {@code GET /api/modules/{id}/status} — consommé par le
 * {@code moduleGuard} Angular (pivot-ui) avant navigation vers une route d'un module.
 *
 * <p>Distinct de {@link ModuleDto} : {@link ModuleDto} (issu de
 * {@link ModuleRegistryService#getModulesForTenant}) évalue
 * {@link fr.pivot.core.modules.PivotModule#isEnabled} — logique métier propre au module.
 * Ce DTO reflète l'état persistant d'activation admin
 * ({@link fr.pivot.core.modules.ModuleActivationService}) — source de vérité pour le guard.
 *
 * <p><b>Sémantique HTTP du endpoint</b> (décision documentée EN03.2 / US03.2.2) :
 * <ul>
 *   <li>module enregistré et désactivé pour le tenant → <b>200</b> avec
 *       {@code enabled=false} (l'utilisateur authentifié est autorisé à connaître le
 *       statut du module ; seul l'usage du module est bloqué, pas la lecture de son
 *       statut) ;</li>
 *   <li>module enregistré et activé pour le tenant → <b>200</b> avec {@code enabled=true} ;</li>
 *   <li>identifiant de module non enregistré dans le
 *       {@link fr.pivot.core.modules.ModuleRegistry} → <b>404</b> (ressource inexistante,
 *       cas distinct d'un module simplement désactivé — voir
 *       {@link fr.pivot.core.modules.UnknownModuleException}).</li>
 * </ul>
 * Aucun 403 n'est retourné par ce endpoint : le guard Angular traite {@code enabled=false}
 * comme équivalent fonctionnel d'un accès refusé (redirection + toast), sans avoir besoin
 * d'un code HTTP dédié pour ce cas.
 *
 * @param enabled {@code true} si le module est activé pour le tenant courant
 */
public record ModuleStatusDto(boolean enabled) {
}
