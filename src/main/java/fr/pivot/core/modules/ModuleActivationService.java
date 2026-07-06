package fr.pivot.core.modules;

import fr.pivot.core.modules.event.ModuleActivatedEvent;
import fr.pivot.core.modules.event.ModuleDeactivatedEvent;
import fr.pivot.core.modules.event.ModuleLifecycleEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Service de gestion de l'état d'activation des modules PIVOT par tenant.
 *
 * <p>Responsabilités :
 * <ul>
 *   <li>persistance de l'état (tenant, module) dans {@code public.module_activations} ;</li>
 *   <li>publication d'événements typés {@link ModuleLifecycleEvent} via
 *       {@link ApplicationEventPublisher} — bus inter-modules, jamais d'appel direct ;</li>
 *   <li>validation que le module ciblé est enregistré dans le {@link ModuleRegistry}.</li>
 * </ul>
 *
 * <p>Sémantique : l'absence de ligne équivaut à désactivé. Un événement n'est publié que
 * sur transition effective d'état (idempotence — pas d'événement dupliqué).
 *
 * <p>Isolation tenant : le {@code tenantId} reçu ici provient exclusivement du
 * {@code TenantContext} du token porteur, résolu par la couche appelante.
 *
 * <p><strong>Observabilité (EN04.3)</strong> : chaque appel réussi à {@link #activate} incrémente
 * le compteur Micrometer {@code pivot.module.activations} (exporté en Prometheus sous
 * {@code pivot_module_activations_total}), tagué {@code module}/{@code tenant} — {@code tenant}
 * porte l'identifiant technique du tenant, jamais un nom ou email (pas de PII dans les tags de
 * métrique, qui sont conservés en clair par le backend Prometheus).
 */
@Service
public class ModuleActivationService {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleActivationService.class);

    private static final String METRIC_ACTIVATIONS = "pivot.module.activations";

    private final ModuleRegistry moduleRegistry;
    private final ModuleActivationRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    /**
     * Construit le service avec ses collaborateurs.
     *
     * @param moduleRegistry registre des modules disponibles
     * @param repository     accès BDD aux états d'activation
     * @param eventPublisher bus d'événements Spring inter-modules
     * @param meterRegistry  registre Micrometer pour le compteur {@link #METRIC_ACTIVATIONS}
     */
    public ModuleActivationService(final ModuleRegistry moduleRegistry,
                                   final ModuleActivationRepository repository,
                                   final ApplicationEventPublisher eventPublisher,
                                   final MeterRegistry meterRegistry) {
        this.moduleRegistry = moduleRegistry;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Active un module pour un tenant et publie un {@link ModuleActivatedEvent}
     * si l'état change effectivement.
     *
     * @param tenantId identifiant du tenant (issu du token porteur)
     * @param moduleId identifiant technique du module
     * @return l'état d'activation persisté
     * @throws UnknownModuleException si le module n'est pas enregistré dans le registre
     */
    @Transactional
    public ModuleActivation activate(final Long tenantId, final String moduleId) {
        final ModuleActivation result = changeState(tenantId, moduleId, true);
        meterRegistry.counter(METRIC_ACTIVATIONS, "module", moduleId, "tenant", String.valueOf(tenantId))
                .increment();
        return result;
    }

    /**
     * Désactive un module pour un tenant et publie un {@link ModuleDeactivatedEvent}
     * si l'état change effectivement.
     *
     * @param tenantId identifiant du tenant (issu du token porteur)
     * @param moduleId identifiant technique du module
     * @return l'état d'activation persisté
     * @throws UnknownModuleException si le module n'est pas enregistré dans le registre
     */
    @Transactional
    public ModuleActivation deactivate(final Long tenantId, final String moduleId) {
        return changeState(tenantId, moduleId, false);
    }

    /**
     * Indique si un module est activé pour un tenant.
     *
     * <p>Défaut sûr : aucune ligne en BDD = désactivé.
     *
     * @param tenantId identifiant du tenant
     * @param moduleId identifiant technique du module
     * @return {@code true} uniquement si une ligne existe avec {@code enabled = true}
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(final Long tenantId, final String moduleId) {
        return repository.findByTenantIdAndModuleId(tenantId, moduleId)
                .map(ModuleActivation::isEnabled)
                .orElse(false);
    }

    private ModuleActivation changeState(final Long tenantId, final String moduleId, final boolean enabled) {
        if (!moduleRegistry.isRegistered(moduleId)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("event=MODULE_ACTIVATION_REJECTED reason=unknown_module tenantId={} moduleId={}",
                        tenantId, sanitizeForLog(moduleId));
            }
            throw new UnknownModuleException(moduleId);
        }

        final Optional<ModuleActivation> existing = repository.findByTenantIdAndModuleId(tenantId, moduleId);
        final boolean previousState = existing.map(ModuleActivation::isEnabled).orElse(false);
        final ModuleActivation activation = existing.orElseGet(() -> new ModuleActivation(tenantId, moduleId));
        activation.setEnabled(enabled);
        final ModuleActivation saved = repository.save(activation);

        if (previousState != enabled) {
            final ModuleLifecycleEvent event = enabled
                    ? new ModuleActivatedEvent(tenantId, moduleId, Instant.now())
                    : new ModuleDeactivatedEvent(tenantId, moduleId, Instant.now());
            if (LOG.isInfoEnabled()) {
                LOG.info("event={} tenantId={} moduleId={}",
                        enabled ? "MODULE_ACTIVATED" : "MODULE_DEACTIVATED", tenantId, sanitizeForLog(moduleId));
            }
            eventPublisher.publishEvent(event);
        } else if (LOG.isInfoEnabled()) {
            LOG.info("event=MODULE_ACTIVATION_NOOP tenantId={} moduleId={} enabled={}",
                    tenantId, sanitizeForLog(moduleId), enabled);
        }
        return saved;
    }

    /**
     * Neutralise les caractères de contrôle CR/LF d'une valeur avant de la loguer.
     *
     * <p>{@code moduleId} provient in fine d'un {@code @PathVariable}/body de la couche
     * appelante — donnée utilisateur non fiable. Sans neutralisation, un identifiant
     * contenant {@code \r} ou {@code \n} permettrait d'injecter de fausses lignes de log
     * (CWE-117 / log forging) dans un fichier de log en texte brut. La valeur n'est jamais
     * utilisée ailleurs que dans un message de log — la logique métier continue d'utiliser
     * l'identifiant d'origine.
     *
     * @param value valeur potentiellement non fiable à journaliser
     * @return valeur sans retour chariot ni saut de ligne, sûre pour un message de log
     */
    private static String sanitizeForLog(final String value) {
        return value == null ? "null" : value.replaceAll("[\r\n]", "_");
    }
}
