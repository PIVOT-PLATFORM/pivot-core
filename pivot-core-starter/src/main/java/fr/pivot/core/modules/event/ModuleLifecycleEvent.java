package fr.pivot.core.modules.event;

import java.time.Instant;

/**
 * Événement typé du cycle de vie d'activation d'un module PIVOT pour un tenant.
 *
 * <p>Bus d'événements inter-modules : ces événements sont publiés via
 * {@link org.springframework.context.ApplicationEventPublisher} par
 * {@link fr.pivot.core.modules.ModuleActivationService}. Les modules consommateurs
 * réagissent avec {@code @EventListener} — jamais d'appel direct inter-modules.
 */
public sealed interface ModuleLifecycleEvent permits ModuleActivatedEvent, ModuleDeactivatedEvent {

    /**
     * Tenant concerné par le changement d'état.
     *
     * @return identifiant du tenant
     */
    Long tenantId();

    /**
     * Module concerné par le changement d'état.
     *
     * @return identifiant technique du module
     */
    String moduleId();

    /**
     * Horodatage du changement d'état.
     *
     * @return instant de publication
     */
    Instant occurredAt();
}
