package fr.pivot.core.modules.event;

import java.time.Instant;

/**
 * Événement publié quand un module passe à l'état désactivé pour un tenant.
 *
 * @param tenantId   identifiant du tenant concerné
 * @param moduleId   identifiant technique du module désactivé
 * @param occurredAt horodatage du changement d'état
 */
public record ModuleDeactivatedEvent(Long tenantId, String moduleId, Instant occurredAt)
        implements ModuleLifecycleEvent {
}
