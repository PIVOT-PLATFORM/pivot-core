package fr.pivot.modules.api;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.core.modules.ModuleOverride;
import fr.pivot.core.modules.ModuleOverrideRepository;
import fr.pivot.plan.entity.Plan;
import fr.pivot.plan.repository.PlanRepository;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration (PostgreSQL via Testcontainers, contexte Spring réel) pour US03.3.3
 * « Admin tenant voit uniquement modules de son plan ».
 *
 * <p>Traçabilité AC :
 * <ul>
 *   <li>{@code GET /api/admin/modules} filtre par plan du tenant (+ overrides SUPER_ADMIN) —
 *       exercé ici directement au niveau {@link AdminModuleListService}, la couche HTTP
 *       ({@link AdminModuleController#list()}) n'ajoutant que la résolution RBAC/tenant, déjà
 *       couverte par {@code AdminModuleActivationIntegrationTest#list_*} ;</li>
 *   <li>2 tenants sur 2 plans différents (bundles de modules distincts) → listes filtrées
 *       distinctes, sans fuite cross-tenant ;</li>
 *   <li>module hors plan absent de la liste — jamais {@code 403} ({@link AdminModuleListService}
 *       ne lève aucune exception, il ne fait que filtrer) ;</li>
 *   <li>override SUPER_ADMIN actif visible hors plan, {@code source = "override"} ; override
 *       désactivé hors plan reste invisible.</li>
 * </ul>
 *
 * <p><strong>Réutilise le contexte Spring de {@code AdminModuleActivationIntegrationTest}</strong>
 * ({@code @Import(AdminModuleActivationIntegrationTest.TestModuleConfig.class)}, exactement la
 * même classe, pas une copie) plutôt que de déclarer sa propre configuration — voir la Javadoc
 * de {@code TestModuleConfig} pour la raison (évite un pool HikariCP/contexte Spring
 * supplémentaire contre le conteneur Postgres Testcontainers partagé). Les 3 modules nécessaires
 * ici (bundlé dans les deux plans, bundlé dans un seul, absent des deux) sont donc : le module
 * déjà déclaré par cette configuration partagée ({@code admin-it-test-module}, réutilisé ici
 * comme module « toujours dans le plan »), plus {@code list-it-roadmap} et {@code list-it-quiz}
 * (ajoutés à cette même configuration partagée par cette US).
 */
@Import(AdminModuleActivationIntegrationTest.TestModuleConfig.class)
class AdminModuleListIntegrationTest extends AbstractIntegrationTest {

    /** Réutilise le module déjà déclaré par {@code AdminModuleActivationIntegrationTest}. */
    private static final String MODULE_WHITEBOARD = "admin-it-test-module";
    private static final String MODULE_ROADMAP = "list-it-roadmap";
    private static final String MODULE_QUIZ = "list-it-quiz";

    @Autowired
    private AdminModuleListService adminModuleListService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private ModuleOverrideRepository moduleOverrideRepository;

    // ----------------------------------------------------------------
    // AC : 2 tenants de plans différents
    // ----------------------------------------------------------------

    @Test
    void list_shouldFilterDifferently_forTwoTenantsOnDifferentPlans() {
        final Plan planStarter = createPlan(Set.of(MODULE_WHITEBOARD));
        final Plan planPro = createPlan(Set.of(MODULE_WHITEBOARD, MODULE_ROADMAP));
        final Tenant tenantStarter = createTenant(planStarter.getId());
        final Tenant tenantPro = createTenant(planPro.getId());

        final List<AdminModuleDto> starterModules = adminModuleListService.list(tenantStarter.getId());
        final List<AdminModuleDto> proModules = adminModuleListService.list(tenantPro.getId());

        assertThat(starterModules).extracting(AdminModuleDto::id).containsExactlyInAnyOrder(MODULE_WHITEBOARD);
        assertThat(proModules).extracting(AdminModuleDto::id)
                .containsExactlyInAnyOrder(MODULE_WHITEBOARD, MODULE_ROADMAP);
        assertThat(starterModules).extracting(AdminModuleDto::id).doesNotContain(MODULE_QUIZ);
        assertThat(proModules).extracting(AdminModuleDto::id).doesNotContain(MODULE_QUIZ);
        assertThat(starterModules).allSatisfy(dto -> assertThat(dto.source()).isEqualTo("plan"));
        assertThat(proModules).allSatisfy(dto -> assertThat(dto.source()).isEqualTo("plan"));
    }

    @Test
    void list_shouldReturnAllModules_whenTenantHasNoPlanAssigned() {
        final Tenant tenant = createTenant(null);

        final List<AdminModuleDto> modules = adminModuleListService.list(tenant.getId());

        assertThat(modules).extracting(AdminModuleDto::id)
                .containsExactlyInAnyOrder(MODULE_WHITEBOARD, MODULE_ROADMAP, MODULE_QUIZ);
        assertThat(modules).allSatisfy(dto -> assertThat(dto.source()).isEqualTo("plan"));
    }

    // ----------------------------------------------------------------
    // AC : override SUPER_ADMIN visible si actif, source = "override"
    // ----------------------------------------------------------------

    @Test
    void list_shouldIncludeModuleViaOverride_whenOutsideOfPlan() {
        final Plan planStarter = createPlan(Set.of(MODULE_WHITEBOARD));
        final Tenant tenant = createTenant(planStarter.getId());
        moduleOverrideRepository.save(new ModuleOverride(tenant.getId(), MODULE_QUIZ, true));

        final List<AdminModuleDto> modules = adminModuleListService.list(tenant.getId());

        assertThat(modules).extracting(AdminModuleDto::id)
                .containsExactlyInAnyOrder(MODULE_WHITEBOARD, MODULE_QUIZ);
        final AdminModuleDto quizDto = modules.stream()
                .filter(m -> MODULE_QUIZ.equals(m.id())).findFirst().orElseThrow();
        assertThat(quizDto.source()).isEqualTo("override");
        assertThat(quizDto.enabled()).isTrue();
    }

    @Test
    void list_shouldExcludeModule_whenOutsideOfPlanAndOverrideDisabled() {
        final Plan planStarter = createPlan(Set.of(MODULE_WHITEBOARD));
        final Tenant tenant = createTenant(planStarter.getId());
        moduleOverrideRepository.save(new ModuleOverride(tenant.getId(), MODULE_QUIZ, false));

        final List<AdminModuleDto> modules = adminModuleListService.list(tenant.getId());

        assertThat(modules).extracting(AdminModuleDto::id).containsExactly(MODULE_WHITEBOARD);
    }

    @Test
    void list_shouldIsolateTenants_whenOnlyOneHasAnOverride() {
        final Plan plan = createPlan(Set.of(MODULE_WHITEBOARD));
        final Tenant tenantA = createTenant(plan.getId());
        final Tenant tenantB = createTenant(plan.getId());
        moduleOverrideRepository.save(new ModuleOverride(tenantA.getId(), MODULE_QUIZ, true));

        final List<AdminModuleDto> tenantAModules = adminModuleListService.list(tenantA.getId());
        final List<AdminModuleDto> tenantBModules = adminModuleListService.list(tenantB.getId());

        assertThat(tenantAModules).extracting(AdminModuleDto::id)
                .containsExactlyInAnyOrder(MODULE_WHITEBOARD, MODULE_QUIZ);
        assertThat(tenantBModules).extracting(AdminModuleDto::id).containsExactly(MODULE_WHITEBOARD);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private Plan createPlan(final Set<String> moduleIds) {
        final Plan plan = new Plan();
        plan.setName("list-it-plan-" + System.nanoTime());
        plan.setModuleIds(moduleIds);
        return planRepository.save(plan);
    }

    private Tenant createTenant(final Long billingPlanId) {
        final Tenant tenant = new Tenant();
        tenant.setSlug("admin-module-list-it-" + System.nanoTime());
        tenant.setName("Admin Module List IT Tenant");
        tenant.setBillingPlanId(billingPlanId);
        return tenantRepository.save(tenant);
    }
}
