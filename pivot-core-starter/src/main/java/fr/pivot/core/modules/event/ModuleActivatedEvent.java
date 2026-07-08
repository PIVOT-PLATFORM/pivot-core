package fr.pivot.core.modules.event;

import java.time.Instant;

/**
 * Événement publié quand un module passe à l'état activé pour un tenant.
 *
 * @param tenantId   identifiant du tenant concerné
 * @param moduleId   identifiant technique du module activé
 * @param occurredAt horodatage du changement d'état
 */
public record ModuleActivatedEvent(Long tenantId, String moduleId, Instant occurredAt)
        implements ModuleLifecycleEvent {
}
