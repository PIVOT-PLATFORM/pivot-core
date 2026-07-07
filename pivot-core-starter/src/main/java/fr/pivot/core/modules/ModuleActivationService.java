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
 *   <li>persistance des overrides SUPER_ADMIN (US03.3.2) dans {@code public.module_overrides},
 *       qui priment sur {@code module_activations} — voir {@link #isEnabled(Long, String)} ;</li>
 *   <li>publication d'événements typés {@link ModuleLifecycleEvent} via
 *       {@link ApplicationEventPublisher} — bus inter-modules, jamais d'appel direct ;</li>
 *   <li>validation que le module ciblé est enregistré dans le {@link ModuleRegistry}.</li>
 * </ul>
 *
 * <p>Sémantique : l'absence de ligne équivaut à désactivé. Un événement n'est publié que
 * sur transition <em>effective</em> — c'est-à-dire une transition de {@link #isEnabled}, la
 * résolution combinée override + activation, jamais seulement de l'une des deux tables prise
 * isolément (voir {@link #changeState} et {@link #setOverride}/{@link #removeOverride}).
 *
 * <p><strong>Isolation des autorités (US03.3.2) :</strong> {@code module_activations} porte le
 * choix de l'administrateur <em>du tenant</em> ; {@code module_overrides} porte une décision
 * plateforme du {@code SUPER_ADMIN} qui prend explicitement le pas dessus. Un
 * {@link #setOverride}/{@link #removeOverride} ne touche jamais {@code module_activations}, et
 * {@link #activate}/{@link #deactivate} (choix de l'admin de tenant) ne touchent jamais
 * {@code module_overrides} — seule {@link #isEnabled} compose les deux : tant qu'un override est
 * actif, l'admin du tenant peut continuer à activer/désactiver son module (la valeur est
 * persistée, elle "prendra effet" une fois l'override retiré), mais cela ne change rien à ce qui
 * est effectivement servi au tenant tant que l'override reste en place.
 *
 * <p>Isolation tenant : le {@code tenantId} reçu ici provient exclusivement du
 * {@code TenantContext} du token porteur (pour {@link #activate}/{@link #deactivate}) ou d'un
 * {@code tenantId} explicitement validé par la couche appelante SUPER_ADMIN (pour
 * {@link #setOverride}/{@link #removeOverride} — voir {@code fr.pivot.modules.api.ModuleOverrideService}).
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
    private final ModuleOverrideRepository overrideRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    /**
     * Construit le service avec ses collaborateurs.
     *
     * @param moduleRegistry     registre des modules disponibles
     * @param repository         accès BDD aux états d'activation (choix de l'admin de tenant)
     * @param overrideRepository accès BDD aux overrides SUPER_ADMIN (US03.3.2)
     * @param eventPublisher     bus d'événements Spring inter-modules
     * @param meterRegistry      registre Micrometer pour le compteur {@link #METRIC_ACTIVATIONS}
     */
    public ModuleActivationService(final ModuleRegistry moduleRegistry,
                                   final ModuleActivationRepository repository,
                                   final ModuleOverrideRepository overrideRepository,
                                   final ApplicationEventPublisher eventPublisher,
                                   final MeterRegistry meterRegistry) {
        this.moduleRegistry = moduleRegistry;
        this.repository = repository;
        this.overrideRepository = overrideRepository;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Active un module pour un tenant et publie un {@link ModuleActivatedEvent}
     * si l'état <em>effectif</em> change.
     *
     * <p>Si un override SUPER_ADMIN est actif pour ce couple (tenant, module), cet appel
     * persiste tout de même le nouveau choix dans {@code module_activations} (il "prendra effet"
     * dès que l'override sera retiré), mais aucun événement n'est publié si l'override maintient
     * l'état effectif inchangé — voir la Javadoc de classe.
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
     * si l'état <em>effectif</em> change (voir {@link #activate} pour la sémantique
     * complète en présence d'un override).
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
     * Indique si un module est <em>effectivement</em> activé pour un tenant.
     *
     * <p>Ordre de résolution (US03.3.2) :
     * <ol>
     *   <li>un override SUPER_ADMIN actif pour ce couple (tenant, module) — s'il existe, sa
     *       valeur gagne toujours, quel que soit l'état de {@code module_activations} ;</li>
     *   <li>à défaut, l'état persisté dans {@code module_activations} (choix de l'admin de
     *       tenant) — défaut sûr : aucune ligne en BDD = désactivé.</li>
     * </ol>
     *
     * @param tenantId identifiant du tenant
     * @param moduleId identifiant technique du module
     * @return {@code true} si le module est effectivement activé pour ce tenant
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(final Long tenantId, final String moduleId) {
        return overrideRepository.findByTenantIdAndModuleId(tenantId, moduleId)
                .map(ModuleOverride::isEnabled)
                .orElseGet(() -> repository.findByTenantIdAndModuleId(tenantId, moduleId)
                        .map(ModuleActivation::isEnabled)
                        .orElse(false));
    }

    /**
     * Pose ou remplace un override SUPER_ADMIN forçant l'état d'un module pour un tenant,
     * quel que soit le choix courant de l'admin de ce tenant (US03.3.2).
     *
     * <p>RBAC ({@code ROLE_SUPER_ADMIN}) et validation d'existence du tenant : portées par
     * l'appelant SUPER_ADMIN (voir {@code fr.pivot.modules.api.ModuleOverrideService}) — ce
     * service ne connaît que la mécanique de résolution, pas la politique d'autorisation
     * (même séparation que {@link #activate}/{@code AdminModuleActivationService}).
     *
     * @param tenantId identifiant du tenant ciblé (déjà validé existant par l'appelant)
     * @param moduleId identifiant technique du module à forcer
     * @param enabled  valeur forcée (activé/désactivé)
     * @return l'override persisté
     * @throws UnknownModuleException si le module n'est pas enregistré dans le registre
     */
    @Transactional
    public ModuleOverride setOverride(final Long tenantId, final String moduleId, final boolean enabled) {
        if (!moduleRegistry.isRegistered(moduleId)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("event=MODULE_OVERRIDE_REJECTED reason=unknown_module tenantId={} moduleId={}",
                        tenantId, sanitizeForLog(moduleId));
            }
            throw new UnknownModuleException(moduleId);
        }

        // Une seule lecture de overrideRepository, réutilisée à la fois pour calculer l'état
        // effectif AVANT écriture et pour obtenir la ligne à muter (ou en créer une) — jamais de
        // seconde lecture après l'écriture pour déduire le nouvel état effectif : celui-ci est
        // connu analytiquement (un override fraîchement posé gagne toujours, donc l'état effectif
        // après cet appel est exactement `enabled`).
        final Optional<ModuleOverride> existingOverride = overrideRepository.findByTenantIdAndModuleId(tenantId, moduleId);
        final boolean previousEffective = existingOverride
                .map(ModuleOverride::isEnabled)
                .orElseGet(() -> repository.findByTenantIdAndModuleId(tenantId, moduleId)
                        .map(ModuleActivation::isEnabled)
                        .orElse(false));

        final ModuleOverride override = existingOverride.orElseGet(() -> new ModuleOverride(tenantId, moduleId, enabled));
        override.setEnabled(enabled);
        final ModuleOverride saved = overrideRepository.save(override);

        if (LOG.isInfoEnabled()) {
            LOG.info("event=MODULE_OVERRIDE_SET tenantId={} moduleId={} enabled={}",
                    tenantId, sanitizeForLog(moduleId), enabled);
        }
        publishIfTransition(tenantId, moduleId, previousEffective, enabled);
        return saved;
    }

    /**
     * Retire l'override SUPER_ADMIN d'un couple (tenant, module), s'il existe — le module
     * revient au comportement porté par {@code module_activations} (US03.3.2).
     *
     * <p>Idempotent : aucune erreur si aucun override n'existait (revient simplement à l'état
     * {@code module_activations} déjà en vigueur, sans rien changer).
     *
     * @param tenantId identifiant du tenant ciblé (déjà validé existant par l'appelant)
     * @param moduleId identifiant technique du module dont l'override doit être retiré
     * @return l'état effectif du module après retrait de l'override (résultat de
     *     {@link #isEnabled(Long, String)}, désormais basé uniquement sur
     *     {@code module_activations})
     * @throws UnknownModuleException si le module n'est pas enregistré dans le registre
     */
    @Transactional
    public boolean removeOverride(final Long tenantId, final String moduleId) {
        if (!moduleRegistry.isRegistered(moduleId)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("event=MODULE_OVERRIDE_REMOVAL_REJECTED reason=unknown_module tenantId={} moduleId={}",
                        tenantId, sanitizeForLog(moduleId));
            }
            throw new UnknownModuleException(moduleId);
        }

        final Optional<ModuleOverride> existingOverride = overrideRepository.findByTenantIdAndModuleId(tenantId, moduleId);
        // Toujours nécessaire (que l'override existe ou non) : c'est à la fois l'état effectif
        // APRÈS retrait (retour au comportement module_activations) et, si aucun override
        // n'existait, l'état effectif d'AVANT — une seule lecture, réutilisée pour les deux.
        final boolean activationState = repository.findByTenantIdAndModuleId(tenantId, moduleId)
                .map(ModuleActivation::isEnabled)
                .orElse(false);
        final boolean previousEffective = existingOverride.map(ModuleOverride::isEnabled).orElse(activationState);

        final long deleted = overrideRepository.deleteByTenantIdAndModuleId(tenantId, moduleId);

        if (LOG.isInfoEnabled()) {
            LOG.info("event=MODULE_OVERRIDE_REMOVED tenantId={} moduleId={} existed={}",
                    tenantId, sanitizeForLog(moduleId), deleted > 0);
        }
        publishIfTransition(tenantId, moduleId, previousEffective, activationState);
        return activationState;
    }

    private ModuleActivation changeState(final Long tenantId, final String moduleId, final boolean enabled) {
        if (!moduleRegistry.isRegistered(moduleId)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("event=MODULE_ACTIVATION_REJECTED reason=unknown_module tenantId={} moduleId={}",
                        tenantId, sanitizeForLog(moduleId));
            }
            throw new UnknownModuleException(moduleId);
        }

        // Une seule lecture de chaque repository : previousEffective est dérivé de l'état AVANT
        // mutation ; newEffective est connu analytiquement (si un override est actif, il continue
        // de piloter l'état effectif après cet appel — voir la Javadoc de classe — sinon c'est
        // exactement `enabled`) — jamais de seconde lecture après écriture.
        final Optional<ModuleOverride> override = overrideRepository.findByTenantIdAndModuleId(tenantId, moduleId);
        final Optional<ModuleActivation> existing = repository.findByTenantIdAndModuleId(tenantId, moduleId);
        final boolean previousEffective = override
                .map(ModuleOverride::isEnabled)
                .orElseGet(() -> existing.map(ModuleActivation::isEnabled).orElse(false));

        final ModuleActivation activation = existing.orElseGet(() -> new ModuleActivation(tenantId, moduleId));
        activation.setEnabled(enabled);
        final ModuleActivation saved = repository.save(activation);

        if (LOG.isInfoEnabled()) {
            LOG.info("event={} tenantId={} moduleId={}",
                    enabled ? "MODULE_ACTIVATED" : "MODULE_DEACTIVATED", tenantId, sanitizeForLog(moduleId));
        }
        final boolean newEffective = override.map(ModuleOverride::isEnabled).orElse(enabled);
        publishIfTransition(tenantId, moduleId, previousEffective, newEffective);
        return saved;
    }

    /**
     * Publie l'événement de cycle de vie correspondant si l'état effectif transitionne
     * réellement entre {@code previousEffective} et {@code newEffective} — jamais d'événement
     * dupliqué sur un no-op (idempotence, même motif que le comportement historique de
     * {@link #changeState}, désormais étendu à {@link #setOverride}/{@link #removeOverride}).
     *
     * <p>Réutilise volontairement {@link ModuleActivatedEvent}/{@link ModuleDeactivatedEvent}
     * plutôt que d'introduire un nouveau type d'événement dédié aux overrides : ces événements
     * documentent une transition de l'état <em>effectif</em> d'un module pour un tenant, quelle
     * qu'en soit la cause (choix de l'admin de tenant ou override SUPER_ADMIN) — c'est
     * exactement la sémantique attendue par leur unique consommateur actuel,
     * {@code ModuleActivationCacheService} (invalidation write-through du cache Redis), qui n'a
     * besoin de connaître ni l'acteur ni la table d'origine, seulement le nouvel état.
     *
     * @param tenantId          tenant concerné
     * @param moduleId          module concerné
     * @param previousEffective état effectif avant l'opération
     * @param newEffective      état effectif après l'opération
     */
    private void publishIfTransition(final Long tenantId, final String moduleId,
                                     final boolean previousEffective, final boolean newEffective) {
        if (previousEffective != newEffective) {
            final ModuleLifecycleEvent event = newEffective
                    ? new ModuleActivatedEvent(tenantId, moduleId, Instant.now())
                    : new ModuleDeactivatedEvent(tenantId, moduleId, Instant.now());
            eventPublisher.publishEvent(event);
        } else if (LOG.isInfoEnabled()) {
            LOG.info("event=MODULE_ACTIVATION_NOOP tenantId={} moduleId={} enabled={}",
                    tenantId, sanitizeForLog(moduleId), newEffective);
        }
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
