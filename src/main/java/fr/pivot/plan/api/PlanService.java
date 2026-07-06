package fr.pivot.plan.api;

import fr.pivot.core.modules.ModuleRegistry;
import fr.pivot.plan.entity.Plan;
import fr.pivot.plan.repository.PlanRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de gestion des plans commerciaux/tarifaires — US03.3.1 « SUPER_ADMIN définit modules
 * disponibles par plan » : création/consultation de {@link Plan}, et gestion de la liste des
 * modules bundlés dans un plan (remplacement complet, ajout unitaire).
 *
 * <p><strong>RBAC porté par ce service, pas le contrôleur :</strong> {@code
 * @PreAuthorize("hasRole('SUPER_ADMIN')")} est évalué par le proxy Spring Method Security
 * ({@code @EnableMethodSecurity}, activé dans {@code SecurityConfig}) sur chaque méthode
 * publique, y compris si un futur appelant interne oublie la vérification côté contrôleur —
 * même motif que {@code SuperAdminTenantService} et {@code AdminModuleActivationService}.
 *
 * <p><strong>Validation des identifiants de module :</strong> tout {@code moduleId} passé à
 * {@link #replaceModules} ou {@link #addModule} est vérifié contre {@link ModuleRegistry#isRegistered}
 * avant d'être persisté — un identifiant non enregistré lève {@link UnknownModuleIdException}
 * ({@code 400}), distincte de {@link fr.pivot.modules.api.ModuleNotInPlanException} (voir sa
 * Javadoc pour la différence de sémantique).
 *
 * <p><strong>Idempotence de l'ajout unitaire :</strong> {@link #addModule} est idempotent — un
 * module déjà présent dans le plan n'est ni dupliqué ni signalé en erreur ; l'appel retourne
 * simplement l'état courant du plan ({@code 200 OK}). Ce choix s'appuie naturellement sur le
 * {@code Set<String>} porté par {@link Plan#getModuleIds()} et la contrainte unique {@code
 * uq_pm_plan_module} — l'AC ne demande aucune sémantique de conflit ({@code 409}) pour ce cas,
 * il n'y a donc pas lieu d'en inventer une.
 *
 * <p><strong>Remplacement complet :</strong> {@link #replaceModules} accepte une liste vide —
 * cas d'usage valide (retire tous les modules du plan), pas une erreur.
 */
@Service
public class PlanService {

    private static final Logger LOG = LoggerFactory.getLogger(PlanService.class);

    private final PlanRepository planRepository;
    private final ModuleRegistry moduleRegistry;

    /**
     * Construit le service avec ses collaborateurs.
     *
     * @param planRepository accès BDD aux plans
     * @param moduleRegistry registre des modules disponibles, source de vérité pour la
     *                       validation des identifiants de module
     */
    public PlanService(final PlanRepository planRepository, final ModuleRegistry moduleRegistry) {
        this.planRepository = planRepository;
        this.moduleRegistry = moduleRegistry;
    }

    /**
     * Crée un nouveau plan, sans module assigné.
     *
     * @param request payload validé (nom du plan)
     * @return le plan créé, liste de modules vide
     * @throws PlanNameAlreadyExistsException si un plan porte déjà ce nom
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public PlanDto createPlan(final CreatePlanRequest request) {
        if (planRepository.findByName(request.name()).isPresent()) {
            throw new PlanNameAlreadyExistsException(request.name());
        }

        final Plan plan = new Plan();
        plan.setName(request.name());
        final Plan saved = planRepository.save(plan);

        if (LOG.isInfoEnabled()) {
            LOG.info("event=SUPERADMIN_PLAN_CREATED planId={} name={}", saved.getId(), sanitizeForLog(saved.getName()));
        }
        return PlanDto.from(saved);
    }

    /**
     * Liste tous les plans de la plateforme — pas de pagination, liste de configuration de
     * petite taille (pas des données à l'échelle tenant).
     *
     * @return tous les plans, dans l'ordre naturel de la BDD
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public List<PlanDto> listPlans() {
        return planRepository.findAll().stream().map(PlanDto::from).toList();
    }

    /**
     * Récupère un plan par son identifiant, avec sa liste de modules.
     *
     * @param planId identifiant du plan recherché
     * @return le plan trouvé
     * @throws PlanNotFoundException si aucun plan ne correspond à {@code planId}
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public PlanDto getPlan(final Long planId) {
        return PlanDto.from(findPlanOrThrow(planId));
    }

    /**
     * Remplace intégralement la liste des modules d'un plan.
     *
     * @param planId    identifiant du plan ciblé (jamais accepté depuis le corps de requête —
     *                  voir {@link SuperAdminPlanController})
     * @param moduleIds nouvel ensemble exact de modules — une liste vide est acceptée (retire
     *                  tous les modules du plan)
     * @return l'état courant (après remplacement) des modules du plan
     * @throws PlanNotFoundException      si aucun plan ne correspond à {@code planId}
     * @throws UnknownModuleIdException   si un des identifiants de module n'est pas enregistré
     *                                    dans le {@link ModuleRegistry}
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public PlanModulesResponse replaceModules(final Long planId, final List<String> moduleIds) {
        final Plan plan = findPlanOrThrow(planId);
        final Set<String> validated = validateModuleIds(moduleIds);

        plan.setModuleIds(validated);
        final Plan saved = planRepository.save(plan);

        if (LOG.isInfoEnabled()) {
            LOG.info("event=SUPERADMIN_PLAN_MODULES_REPLACED planId={} moduleCount={}",
                    planId, saved.getModuleIds().size());
        }
        return toModulesResponse(saved);
    }

    /**
     * Ajoute un module unique à un plan — idempotent (voir Javadoc de classe).
     *
     * @param planId   identifiant du plan ciblé (jamais accepté depuis le corps de requête)
     * @param moduleId identifiant du module à ajouter
     * @return l'état courant (après ajout, ou inchangé si déjà présent) des modules du plan
     * @throws PlanNotFoundException    si aucun plan ne correspond à {@code planId}
     * @throws UnknownModuleIdException si {@code moduleId} n'est pas enregistré dans le {@link ModuleRegistry}
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public PlanModulesResponse addModule(final Long planId, final String moduleId) {
        final Plan plan = findPlanOrThrow(planId);
        if (!moduleRegistry.isRegistered(moduleId)) {
            throw new UnknownModuleIdException(moduleId);
        }

        final boolean added = plan.getModuleIds().add(moduleId);
        final Plan saved = planRepository.save(plan);

        if (LOG.isInfoEnabled()) {
            LOG.info("event=SUPERADMIN_PLAN_MODULE_ADDED planId={} moduleId={} alreadyPresent={}",
                    planId, sanitizeForLog(moduleId), !added);
        }
        return toModulesResponse(saved);
    }

    /**
     * Retourne la liste courante des modules d'un plan.
     *
     * @param planId identifiant du plan ciblé
     * @return les modules actuellement bundlés dans ce plan
     * @throws PlanNotFoundException si aucun plan ne correspond à {@code planId}
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional(readOnly = true)
    public PlanModulesResponse getModules(final Long planId) {
        return toModulesResponse(findPlanOrThrow(planId));
    }

    private Plan findPlanOrThrow(final Long planId) {
        return planRepository.findById(planId).orElseThrow(() -> new PlanNotFoundException(planId));
    }

    private Set<String> validateModuleIds(final List<String> moduleIds) {
        final Set<String> validated = new HashSet<>();
        for (final String moduleId : moduleIds) {
            if (!moduleRegistry.isRegistered(moduleId)) {
                throw new UnknownModuleIdException(moduleId);
            }
            validated.add(moduleId);
        }
        return validated;
    }

    private static PlanModulesResponse toModulesResponse(final Plan plan) {
        return new PlanModulesResponse(plan.getModuleIds().stream().sorted().toList());
    }

    /**
     * Neutralise les caractères de contrôle CR/LF d'une valeur avant de la loguer.
     *
     * <p>{@code name}/{@code moduleId} proviennent in fine d'un {@code @RequestBody}/{@code
     * @PathVariable} — données utilisateur non fiables. Sans neutralisation, une valeur
     * contenant {@code \r} ou {@code \n} permettrait d'injecter de fausses lignes de log
     * (CWE-117 / log forging).
     *
     * @param value valeur potentiellement non fiable à journaliser
     * @return valeur sans retour chariot ni saut de ligne, sûre pour un message de log
     */
    private static String sanitizeForLog(final String value) {
        return value == null ? "null" : value.replaceAll("[\r\n]", "_");
    }
}
