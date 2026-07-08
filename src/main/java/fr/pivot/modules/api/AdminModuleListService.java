package fr.pivot.modules.api;

import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.ModuleOverride;
import fr.pivot.core.modules.ModuleOverrideRepository;
import fr.pivot.core.modules.ModuleRegistry;
import fr.pivot.core.modules.PivotModule;
import fr.pivot.plan.entity.Plan;
import fr.pivot.plan.repository.PlanRepository;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Résout la liste des modules PIVOT visibles pour l'admin d'un tenant, filtrée par le plan
 * commercial souscrit — US03.3.3 « Admin tenant voit uniquement modules de son plan ».
 *
 * <p>Distinct de {@link AdminModuleActivationService} (activation/désactivation) : ce service
 * ne résout que la <em>visibilité</em> pour {@code GET /api/admin/modules}. Il ne touche jamais
 * {@code activate}/{@code deactivate} — bloquer ces actions pour un module hors plan reste hors
 * périmètre (voir l'{@code @implNote} « future enforcement » de {@link Plan}), cette US ne porte
 * que sur ce qui est <em>affiché</em> à l'admin du tenant, jamais sur ce qu'il peut
 * activer/désactiver.
 *
 * <p><strong>Résolution de visibilité</strong> (pour chaque module du {@link ModuleRegistry}) :
 * <ol>
 *   <li>tenant sans plan assigné ({@link Tenant#getBillingPlanId()} {@code null}, ou plan
 *       introuvable) → aucune restriction, tous les modules du registre sont visibles — voir
 *       l'{@code @implNote} ci-dessous ;</li>
 *   <li>tenant avec un plan assigné → visible si {@code moduleId} appartient à
 *       {@link Plan#getModuleIds()} <strong>ou</strong> si un override SUPER_ADMIN actif
 *       ({@code enabled = true}, {@link ModuleOverrideRepository}) existe pour ce couple
 *       (tenant, module) — l'override débloque alors la visibilité au-delà du plan ;</li>
 *   <li>sinon (hors plan, pas d'override actif) → absent de la liste, jamais {@code 403}.</li>
 * </ol>
 *
 * <p><strong>Champ {@code source} de {@link AdminModuleDto}</strong> : {@code "override"}
 * uniquement quand la visibilité du module est due exclusivement à l'override (hors plan) ;
 * {@code "plan"} dans tous les autres cas — y compris un tenant sans plan assigné, et un module
 * du plan par ailleurs neutralisé par un override {@code enabled = false} (son champ
 * {@code enabled} le reflète déjà via {@link ModuleActivationService#isEnabled}, sans changer sa
 * source de visibilité : il reste affiché, seulement désactivé).
 *
 * <p><strong>@implNote — tenant sans plan assigné :</strong> traiter l'absence de plan comme
 * « aucune restriction » (plutôt que « plan vide implicite ») est une clarification PO Agent :
 * l'AC ne couvre que le cas « hors plan » d'un plan <em>existant</em> — la quasi-totalité des
 * tenants existants n'ont pas encore de {@code billingPlanId} assigné (colonne additive
 * fraîchement introduite par US03.3.1, nullable). Leur imposer un filtrage « plan vide » par
 * défaut ferait disparaître tous les modules actuellement visibles pour ces tenants du jour au
 * lendemain — une régression non demandée par l'AC. Le filtrage strict ne s'active qu'une fois
 * un plan effectivement assigné par le SUPER_ADMIN.
 */
@Service
public class AdminModuleListService {

    private final ModuleRegistry moduleRegistry;
    private final ModuleActivationService moduleActivationService;
    private final ModuleOverrideRepository moduleOverrideRepository;
    private final TenantRepository tenantRepository;
    private final PlanRepository planRepository;

    /**
     * Construit le service avec ses collaborateurs.
     *
     * @param moduleRegistry           registre des modules disponibles
     * @param moduleActivationService  résolution de l'état d'activation effectif (override
     *                                 puis {@code module_activations}, déjà composée)
     * @param moduleOverrideRepository accès direct aux overrides SUPER_ADMIN, pour déterminer
     *                                 si un override <em>actif</em> débloque la visibilité
     *                                 d'un module hors plan
     * @param tenantRepository         résolution du plan souscrit par le tenant
     * @param planRepository           résolution des modules bundlés dans un plan
     */
    public AdminModuleListService(
            final ModuleRegistry moduleRegistry,
            final ModuleActivationService moduleActivationService,
            final ModuleOverrideRepository moduleOverrideRepository,
            final TenantRepository tenantRepository,
            final PlanRepository planRepository) {
        this.moduleRegistry = moduleRegistry;
        this.moduleActivationService = moduleActivationService;
        this.moduleOverrideRepository = moduleOverrideRepository;
        this.tenantRepository = tenantRepository;
        this.planRepository = planRepository;
    }

    /**
     * Liste les modules visibles pour l'admin du tenant donné, filtrés par plan + overrides.
     *
     * @param tenantId identifiant du tenant, résolu exclusivement depuis le token porteur par
     *                 l'appelant ({@link AdminModuleController})
     * @return liste des {@link AdminModuleDto} visibles, dans l'ordre de découverte du
     *     {@link ModuleRegistry} — jamais {@code null}, potentiellement vide
     */
    @Transactional(readOnly = true)
    public List<AdminModuleDto> list(final Long tenantId) {
        final Set<String> planModuleIds = resolvePlanModuleIds(tenantId);

        return moduleRegistry.getModules().stream()
                .map(module -> toVisibleDto(module, tenantId, planModuleIds))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Résout l'ensemble des identifiants de module bundlés dans le plan du tenant.
     *
     * @param tenantId identifiant du tenant
     * @return l'ensemble des {@code moduleIds} du plan, ou {@code null} si le tenant n'a aucun
     *     plan assigné (ou introuvable) — {@code null} signifie explicitement « pas de
     *     restriction », distinct d'un ensemble vide (« plan sans aucun module »)
     */
    private Set<String> resolvePlanModuleIds(final Long tenantId) {
        return tenantRepository.findById(tenantId)
                .map(Tenant::getBillingPlanId)
                .flatMap(planRepository::findById)
                .map(Plan::getModuleIds)
                .orElse(null);
    }

    /**
     * Projette un module en {@link AdminModuleDto} s'il est visible pour ce tenant, {@code null}
     * sinon (filtré hors de la liste par l'appelant).
     *
     * @param module        module du registre à évaluer
     * @param tenantId      tenant courant
     * @param planModuleIds ensemble des modules du plan du tenant, ou {@code null} si aucune
     *                       restriction ne s'applique (voir {@link #resolvePlanModuleIds})
     * @return le DTO si visible, {@code null} si le module doit être absent de la liste
     */
    private AdminModuleDto toVisibleDto(final PivotModule module, final Long tenantId,
                                        final Set<String> planModuleIds) {
        final String moduleId = module.getId();
        final boolean inPlan = planModuleIds == null || planModuleIds.contains(moduleId);
        final boolean overrideGrantsVisibility = moduleOverrideRepository
                .findByTenantIdAndModuleId(tenantId, moduleId)
                .map(ModuleOverride::isEnabled)
                .orElse(false);

        if (!inPlan && !overrideGrantsVisibility) {
            return null;
        }

        final boolean enabled = moduleActivationService.isEnabled(tenantId, moduleId);
        final String source = inPlan ? "plan" : "override";
        return new AdminModuleDto(moduleId, module.getName(), enabled, module.getDescription(), source);
    }
}
