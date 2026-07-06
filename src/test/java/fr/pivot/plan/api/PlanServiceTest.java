package fr.pivot.plan.api;

import fr.pivot.core.modules.ModuleRegistry;
import fr.pivot.plan.entity.Plan;
import fr.pivot.plan.repository.PlanRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link PlanService} — US03.3.1 « SUPER_ADMIN définit modules disponibles
 * par plan ».
 *
 * <p>Le RBAC ({@code @PreAuthorize}) n'est pas exercé ici (service instancié directement, hors
 * proxy Spring Method Security) — couvert par {@link SuperAdminPlanIntegrationTest}. Focus ici
 * sur la logique métier : validation des identifiants de module, gestion du {@code planId}
 * inconnu, idempotence de l'ajout unitaire, sémantique de remplacement complet.
 *
 * <table>
 *   <caption>Traçabilité AC → test</caption>
 *   <tr><th>AC</th><th>Test</th></tr>
 *   <tr><td>Entité Plan avec association M-N modules</td>
 *       <td>{@link #createPlan_persistsPlan_withEmptyModuleList()}</td></tr>
 *   <tr><td>Nom de plan unique</td>
 *       <td>{@link #createPlan_throwsAlreadyExists_whenNameTaken()}</td></tr>
 *   <tr><td>PUT remplacement complet</td>
 *       <td>{@link #replaceModules_replacesExactSet_whenAllModulesRegistered()}</td></tr>
 *   <tr><td>PUT liste vide acceptée (retire tous les modules)</td>
 *       <td>{@link #replaceModules_clearsAllModules_whenEmptyListGiven()}</td></tr>
 *   <tr><td>PUT — module inconnu du registre → erreur</td>
 *       <td>{@link #replaceModules_throwsUnknownModuleId_whenAnyModuleUnregistered()}</td></tr>
 *   <tr><td>PUT — plan inconnu → erreur</td>
 *       <td>{@link #replaceModules_throwsPlanNotFound_whenPlanMissing()}</td></tr>
 *   <tr><td>POST ajout unitaire</td>
 *       <td>{@link #addModule_addsModule_whenRegisteredAndAbsent()}</td></tr>
 *   <tr><td>POST ajout unitaire idempotent</td>
 *       <td>{@link #addModule_isIdempotent_whenModuleAlreadyPresent()}</td></tr>
 *   <tr><td>POST — module inconnu du registre → erreur</td>
 *       <td>{@link #addModule_throwsUnknownModuleId_whenModuleUnregistered()}</td></tr>
 *   <tr><td>POST — plan inconnu → erreur</td>
 *       <td>{@link #addModule_throwsPlanNotFound_whenPlanMissing()}</td></tr>
 *   <tr><td>GET /modules retourne la liste courante triée</td>
 *       <td>{@link #getModules_returnsSortedCurrentList()}</td></tr>
 *   <tr><td>GET /plans/{planId} — plan inconnu → erreur</td>
 *       <td>{@link #getPlan_throwsPlanNotFound_whenPlanMissing()}</td></tr>
 *   <tr><td>GET /plans liste tous les plans</td>
 *       <td>{@link #listPlans_mapsAllPlans()}</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanServiceTest {

    private static final Long PLAN_ID = 7L;
    private static final String REGISTERED_MODULE = "whiteboard";
    private static final String OTHER_REGISTERED_MODULE = "roadmap";
    private static final String UNKNOWN_MODULE = "ghost-module";

    @Mock
    private PlanRepository planRepository;

    @Mock
    private ModuleRegistry moduleRegistry;

    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(planRepository, moduleRegistry);
        when(moduleRegistry.isRegistered(REGISTERED_MODULE)).thenReturn(true);
        when(moduleRegistry.isRegistered(OTHER_REGISTERED_MODULE)).thenReturn(true);
        when(moduleRegistry.isRegistered(UNKNOWN_MODULE)).thenReturn(false);
        when(planRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ----------------------------------------------------------------
    // createPlan
    // ----------------------------------------------------------------

    @Test
    void createPlan_persistsPlan_withEmptyModuleList() {
        when(planRepository.findByName("Starter")).thenReturn(Optional.empty());

        final PlanDto result = service.createPlan(new CreatePlanRequest("Starter"));

        assertThat(result.name()).isEqualTo("Starter");
        assertThat(result.moduleIds()).isEmpty();
        verify(planRepository).save(any(Plan.class));
    }

    @Test
    void createPlan_throwsAlreadyExists_whenNameTaken() {
        when(planRepository.findByName("Starter")).thenReturn(Optional.of(new Plan()));
        final CreatePlanRequest request = new CreatePlanRequest("Starter");

        assertThatThrownBy(() -> service.createPlan(request))
                .isInstanceOf(PlanNameAlreadyExistsException.class);

        verify(planRepository, never()).save(any());
    }

    // ----------------------------------------------------------------
    // listPlans / getPlan
    // ----------------------------------------------------------------

    @Test
    void listPlans_mapsAllPlans() {
        final Plan planA = buildPlan(1L, "Starter", Set.of());
        final Plan planB = buildPlan(2L, "Pro", Set.of(REGISTERED_MODULE));
        when(planRepository.findAll()).thenReturn(List.of(planA, planB));

        final List<PlanDto> result = service.listPlans();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PlanDto::name).containsExactly("Starter", "Pro");
    }

    @Test
    void getPlan_returnsPlanWithModules_whenFound() {
        final Plan plan = buildPlan(PLAN_ID, "Pro", Set.of(REGISTERED_MODULE));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        final PlanDto result = service.getPlan(PLAN_ID);

        assertThat(result.id()).isEqualTo(PLAN_ID);
        assertThat(result.moduleIds()).containsExactly(REGISTERED_MODULE);
    }

    @Test
    void getPlan_throwsPlanNotFound_whenPlanMissing() {
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPlan(PLAN_ID))
                .isInstanceOf(PlanNotFoundException.class);
    }

    // ----------------------------------------------------------------
    // replaceModules — PUT full replace
    // ----------------------------------------------------------------

    @Test
    void replaceModules_replacesExactSet_whenAllModulesRegistered() {
        final Plan plan = buildPlan(PLAN_ID, "Pro", new HashSet<>(Set.of("legacy-module-to-drop")));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(moduleRegistry.isRegistered("legacy-module-to-drop")).thenReturn(true);

        final PlanModulesResponse result = service.replaceModules(
                PLAN_ID, List.of(REGISTERED_MODULE, OTHER_REGISTERED_MODULE));

        assertThat(result.moduleIds()).containsExactly(OTHER_REGISTERED_MODULE, REGISTERED_MODULE);
        assertThat(plan.getModuleIds()).containsExactlyInAnyOrder(REGISTERED_MODULE, OTHER_REGISTERED_MODULE);
    }

    @Test
    void replaceModules_clearsAllModules_whenEmptyListGiven() {
        final Plan plan = buildPlan(PLAN_ID, "Pro", new HashSet<>(Set.of(REGISTERED_MODULE)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        final PlanModulesResponse result = service.replaceModules(PLAN_ID, List.of());

        assertThat(result.moduleIds()).isEmpty();
        assertThat(plan.getModuleIds()).isEmpty();
    }

    @Test
    void replaceModules_throwsUnknownModuleId_whenAnyModuleUnregistered() {
        final Plan plan = buildPlan(PLAN_ID, "Pro", Set.of());
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        final List<String> moduleIds = List.of(REGISTERED_MODULE, UNKNOWN_MODULE);

        assertThatThrownBy(() -> service.replaceModules(PLAN_ID, moduleIds))
                .isInstanceOf(UnknownModuleIdException.class);

        assertThat(plan.getModuleIds()).isEmpty();
        verify(planRepository, never()).save(any());
    }

    @Test
    void replaceModules_throwsPlanNotFound_whenPlanMissing() {
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.empty());
        final List<String> moduleIds = List.of(REGISTERED_MODULE);

        assertThatThrownBy(() -> service.replaceModules(PLAN_ID, moduleIds))
                .isInstanceOf(PlanNotFoundException.class);
    }

    // ----------------------------------------------------------------
    // addModule — POST single add, idempotent
    // ----------------------------------------------------------------

    @Test
    void addModule_addsModule_whenRegisteredAndAbsent() {
        final Plan plan = buildPlan(PLAN_ID, "Pro", new HashSet<>());
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        final PlanModulesResponse result = service.addModule(PLAN_ID, REGISTERED_MODULE);

        assertThat(result.moduleIds()).containsExactly(REGISTERED_MODULE);
        assertThat(plan.getModuleIds()).containsExactly(REGISTERED_MODULE);
    }

    @Test
    void addModule_isIdempotent_whenModuleAlreadyPresent() {
        final Plan plan = buildPlan(PLAN_ID, "Pro", new HashSet<>(Set.of(REGISTERED_MODULE)));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        final PlanModulesResponse result = service.addModule(PLAN_ID, REGISTERED_MODULE);

        assertThat(result.moduleIds()).containsExactly(REGISTERED_MODULE);
        assertThat(plan.getModuleIds()).hasSize(1);
    }

    @Test
    void addModule_throwsUnknownModuleId_whenModuleUnregistered() {
        final Plan plan = buildPlan(PLAN_ID, "Pro", new HashSet<>());
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.addModule(PLAN_ID, UNKNOWN_MODULE))
                .isInstanceOf(UnknownModuleIdException.class);

        assertThat(plan.getModuleIds()).isEmpty();
        verify(planRepository, never()).save(any());
    }

    @Test
    void addModule_throwsPlanNotFound_whenPlanMissing() {
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addModule(PLAN_ID, REGISTERED_MODULE))
                .isInstanceOf(PlanNotFoundException.class);
    }

    // ----------------------------------------------------------------
    // getModules
    // ----------------------------------------------------------------

    @Test
    void getModules_returnsSortedCurrentList() {
        final Plan plan = buildPlan(PLAN_ID, "Pro", Set.of(OTHER_REGISTERED_MODULE, REGISTERED_MODULE));
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        final PlanModulesResponse result = service.getModules(PLAN_ID);

        assertThat(result.moduleIds()).containsExactly(OTHER_REGISTERED_MODULE, REGISTERED_MODULE);
    }

    @Test
    void getModules_throwsPlanNotFound_whenPlanMissing() {
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getModules(PLAN_ID))
                .isInstanceOf(PlanNotFoundException.class);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static Plan buildPlan(final Long id, final String name, final Set<String> moduleIds) {
        final Plan plan = new Plan();
        ReflectionTestUtils.setField(plan, "id", id);
        plan.setName(name);
        plan.setModuleIds(new HashSet<>(moduleIds));
        return plan;
    }
}
