package fr.pivot.modules.registry;

import java.util.UUID;

/**
 * Contexte tenant résolu depuis le JWT ou la session courante.
 * Passé à {@link PivotModule#isEnabled(TenantContext)} pour évaluer l'activation.
 */
public record TenantContext(UUID tenantId, String userId, String role) {
}
