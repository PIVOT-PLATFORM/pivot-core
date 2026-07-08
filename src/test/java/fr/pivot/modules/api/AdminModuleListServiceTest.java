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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link AdminModuleListService} — US03.3.3 « Admin tenant voit uniquement
 * modules de son plan ».
 *
 * <p>Traçabilité AC :
 * <ul>
 *   <li>filtrage par plan (+ overrides SUPER_ADMIN) — pas de {@code 403}, simple absence ;</li>
 *   <li>tenant sans plan assigné → aucune restriction (voir {@code @implNote} de
 *       {@link AdminModuleListService}) ;</li>
 *   <li>override actif visible même hors plan, {@code source = "override"} ;</li>
 *   <li>module du plan neutralisé par un override désactivé : reste visible ({@code source =
 *       "plan"}), seul son {@code enabled} reflète la désactivation.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AdminModuleListServiceTest {

    private static final Long TENANT_ID = 42L;
    private static final String MODULE_A = "module-a";
    private static final String MODULE_B = "module-b";

    @Mock
    private ModuleRegistry moduleRegistry;

    @Mock
    private ModuleActivationService moduleActivationService;

    @Mock
    private ModuleOverrideRepository moduleOverrideRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private PlanRepository planRepository;

    private AdminModuleListService service;

    private void init() {
        service = new AdminModuleListService(
                moduleRegistry, moduleActivationService, moduleOverrideRepository, tenantRepository, planRepository);
    }

    // ----------------------------------------------------------------
    // Tenant sans plan assigné — aucune restriction
    // ----------------------------------------------------------------

    @Test
    void list_shouldReturnAllModules_whenTenantHasNoBillingPlanAssigned() {
        init();
        stubRegistry(MODULE_A, MODULE_B);
        stubTenant(TENANT_ID, null);
        noOverrides(MODULE_A, MODULE_B);
        when(moduleActivationService.isEnabled(TENANT_ID, MODULE_A)).thenReturn(true);
        when(moduleActivationService.isEnabled(TENANT_ID, MODULE_B)).thenReturn(false);

        final List<AdminModuleDto> result = service.list(TENANT_ID);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(AdminModuleDto::source).containsOnly("plan");
        assertThat(result).extracting(AdminModuleDto::id).containsExactly(MODULE_A, MODULE_B);
    }

    @Test
    void list_shouldReturnAllModules_whenTenantNotFound() {
        init();
        stubRegistry(MODULE_A);
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());
        noOverrides(MODULE_A);
        when(moduleActivationService.isEnabled(TENANT_ID, MODULE_A)).thenReturn(false);

        final List<AdminModuleDto> result = service.list(TENANT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).source()).isEqualTo("plan");
    }

    @Test
    void list_shouldReturnEmptyList_whenNoModulesRegistered() {
        init();
        when(moduleRegistry.getModules()).thenReturn(List.of());
        lenient().when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

        final List<AdminModuleDto> result = service.list(TENANT_ID);

        assertThat(result).isEmpty();
    }

    // ----------------------------------------------------------------
    // Tenant avec plan assigné — filtrage strict
    // ----------------------------------------------------------------

    @Test
    void list_shouldOnlyReturnPlanModules_whenTenantHasPlanAssigned() {
        init();
        stubRegistry(MODULE_A, MODULE_B);
        stubTenantWithPlan(TENANT_ID, 1L, Set.of(MODULE_A));
        when(moduleOverrideRepository.findByTenantIdAndModuleId(TENANT_ID, MODULE_A)).thenReturn(Optional.empty());
        when(moduleOverrideRepository.findByTenantIdAndModuleId(TENANT_ID, MODULE_B)).thenReturn(Optional.empty());
        when(moduleActivationService.isEnabled(TENANT_ID, MODULE_A)).thenReturn(true);

        final List<AdminModuleDto> result = service.list(TENANT_ID);

        assertThat(result).hasSize(1);
        final AdminModuleDto dto = result.get(0);
        assertThat(dto.id()).isEqualTo(MODULE_A);
        assertThat(dto.source()).isEqualTo("plan");
        assertThat(dto.enabled()).isTrue();
    }

    @Test
    void list_shouldExcludeModule_whenOutOfPlanAndNoOverride() {
        init();
        stubRegistry(MODULE_B);
        stubTenantWithPlan(TENANT_ID, 1L, Set.of(MODULE_A));
        when(moduleOverrideRepository.findByTenantIdAndModuleId(TENANT_ID, MODULE_B)).thenReturn(Optional.empty());

        final List<AdminModuleDto> result = service.list(TENANT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void list_shouldExcludeModule_whenPlanExistsButHasNoModules() {
        init();
        stubRegistry(MODULE_A);
        stubTenantWithPlan(TENANT_ID, 1L, Set.of());
        when(moduleOverrideRepository.findByTenantIdAndModuleId(TENANT_ID, MODULE_A)).thenReturn(Optional.empty());

        final List<AdminModuleDto> result = service.list(TENANT_ID);

        assertThat(result).isEmpty();
    }

    // ----------------------------------------------------------------
    // Override SUPER_ADMIN — visibilité au-delà du plan
    // ----------------------------------------------------------------

    @Test
    void list_shouldIncludeModuleWithOverrideSource_whenOutOfPlanButOverrideEnabled() {
        init();
        stubRegistry(MODULE_B);
        stubTenantWithPlan(TENANT_ID, 1L, Set.of(MODULE_A));
        when(moduleOverrideRepository.findByTenantIdAndModuleId(TENANT_ID, MODULE_B))
                .thenReturn(Optional.of(new ModuleOverride(TENANT_ID, MODULE_B, true)));
        when(moduleActivationService.isEnabled(TENANT_ID, MODULE_B)).thenReturn(true);

        final List<AdminModuleDto> result = service.list(TENANT_ID);

        assertThat(result).hasSize(1);
        final AdminModuleDto dto = result.get(0);
        assertThat(dto.id()).isEqualTo(MODULE_B);
        assertThat(dto.source()).isEqualTo("override");
        assertThat(dto.enabled()).isTrue();
    }

    @Test
    void list_shouldExcludeModule_whenOutOfPlanAndOverrideDisabled() {
        init();
        stubRegistry(MODULE_B);
        stubTenantWithPlan(TENANT_ID, 1L, Set.of(MODULE_A));
        when(moduleOverrideRepository.findByTenantIdAndModuleId(TENANT_ID, MODULE_B))
                .thenReturn(Optional.of(new ModuleOverride(TENANT_ID, MODULE_B, false)));

        final List<AdminModuleDto> result = service.list(TENANT_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void list_shouldKeepPlanSource_whenInPlanModuleHasDisabledOverride() {
        // A SUPER_ADMIN override forcing a plan module OFF for this tenant does not remove it
        // from the list — the module is still bundled in the plan (source stays "plan"), only
        // its effective "enabled" flag reflects the override (already resolved by
        // ModuleActivationService#isEnabled).
        init();
        stubRegistry(MODULE_A);
        stubTenantWithPlan(TENANT_ID, 1L, Set.of(MODULE_A));
        when(moduleOverrideRepository.findByTenantIdAndModuleId(TENANT_ID, MODULE_A))
                .thenReturn(Optional.of(new ModuleOverride(TENANT_ID, MODULE_A, false)));
        when(moduleActivationService.isEnabled(TENANT_ID, MODULE_A)).thenReturn(false);

        final List<AdminModuleDto> result = service.list(TENANT_ID);

        assertThat(result).hasSize(1);
        final AdminModuleDto dto = result.get(0);
        assertThat(dto.source()).isEqualTo("plan");
        assertThat(dto.enabled()).isFalse();
    }

    // ----------------------------------------------------------------
    // Description alimentée depuis PivotModule#getDescription() (Dette S2, fix pivot-core#183)
    // ----------------------------------------------------------------

    @Test
    void list_shouldPopulateDescriptionFromModule() {
        init();
        stubRegistry(MODULE_A);
        stubTenant(TENANT_ID, null);
        noOverrides(MODULE_A);
        when(moduleActivationService.isEnabled(TENANT_ID, MODULE_A)).thenReturn(true);

        final List<AdminModuleDto> result = service.list(TENANT_ID);

        assertThat(result.get(0).description()).isEqualTo("Description de " + MODULE_A);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private void stubRegistry(final String... moduleIds) {
        final List<PivotModule> modules = List.of(moduleIds).stream().map(this::stubModule).toList();
        when(moduleRegistry.getModules()).thenReturn(modules);
    }

    private PivotModule stubModule(final String id) {
        final PivotModule module = mock(PivotModule.class);
        lenient().when(module.getId()).thenReturn(id);
        lenient().when(module.getName()).thenReturn("Module " + id);
        lenient().when(module.getDescription()).thenReturn("Description de " + id);
        return module;
    }

    private void stubTenant(final Long tenantId, final Long billingPlanId) {
        final Tenant tenant = mock(Tenant.class);
        lenient().when(tenant.getBillingPlanId()).thenReturn(billingPlanId);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
    }

    private void stubTenantWithPlan(final Long tenantId, final Long planId, final Set<String> planModuleIds) {
        stubTenant(tenantId, planId);
        final Plan plan = new Plan();
        plan.setName("plan-" + planId);
        plan.setModuleIds(planModuleIds);
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
    }

    private void noOverrides(final String... moduleIds) {
        for (final String moduleId : moduleIds) {
            lenient().when(moduleOverrideRepository.findByTenantIdAndModuleId(TENANT_ID, moduleId))
                    .thenReturn(Optional.empty());
        }
    }
}
