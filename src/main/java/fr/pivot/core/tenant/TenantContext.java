package fr.pivot.core.tenant;

/**
 * Contexte tenant résolu depuis le token d'authentification courant (opaque token ou OIDC).
 *
 * <p>Passé à {@link fr.pivot.core.modules.PivotModule#isEnabled(TenantContext)} pour évaluer
 * l'activation d'un module. Exporté par {@code fr.pivot:pivot-core-starter} — consommé par
 * tous les repos modules ({@code pivot-xxx-core}).
 *
 * <p>Règle d'isolation tenant : ce contexte est construit exclusivement depuis le token
 * porteur (Spring Security) — jamais depuis le body, un query param ou un header custom.
 *
 * <p>{@code tenantId} est l'identifiant BDD natif du tenant ({@code public.tenants.id}),
 * sans conversion — aligné avec {@link fr.pivot.core.modules.ModuleActivationService} et
 * le reste de la couche persistance, qui utilisent tous le même type.
 *
 * @param tenantId identifiant du tenant courant (clé primaire {@code public.tenants.id})
 * @param userId   identifiant de l'utilisateur authentifié
 * @param role     rôle Spring Security de l'utilisateur (ex. {@code ROLE_ADMIN})
 */
public record TenantContext(Long tenantId, String userId, String role) {
}
