package fr.pivot.core.tenant;

import java.util.UUID;

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
 * @param tenantId identifiant du tenant courant
 * @param userId   identifiant de l'utilisateur authentifié
 * @param role     rôle Spring Security de l'utilisateur (ex. {@code ROLE_ADMIN})
 */
public record TenantContext(UUID tenantId, String userId, String role) {
}
