package fr.pivot.plan.api;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.core.modules.PivotModule;
import fr.pivot.core.tenant.TenantContext;
import fr.pivot.plan.entity.Plan;
import fr.pivot.plan.repository.PlanRepository;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'intégration (PostgreSQL via Testcontainers, contexte Spring réel) pour US03.3.1
 * « SUPER_ADMIN définit modules disponibles par plan ».
 *
 * <p>Exerce {@link PlanService} au travers du proxy Spring Method Security réel
 * ({@code @EnableMethodSecurity}) et {@link SuperAdminPlanController} au travers d'un dispatch
 * HTTP réel (filtre Spring Security réel via {@code springSecurity()}) — même motif que
 * {@code SuperAdminTenantIntegrationTest}/{@code AdminModuleActivationIntegrationTest} :
 * {@code @PreAuthorize} n'est significatif que si le bean est résolu via {@code @Autowired}
 * (proxifié), jamais instancié directement.
 *
 * <p>Un module de test ({@code MODULE_ID}) est enregistré dans le {@link
 * fr.pivot.core.modules.ModuleRegistry} via {@link TestModuleConfig}, même pattern que
 * {@code AdminModuleActivationIntegrationTest} — nécessaire pour exercer les chemins où un
 * {@code moduleId} doit être un identifiant réellement enregistré.
 *
 * <table>
 *   <caption>Traçabilité AC → test</caption>
 *   <tr><th>AC</th><th>Test</th></tr>
 *   <tr><td>Requiert ROLE_SUPER_ADMIN — POST /plans</td>
 *       <td>{@link #ac_security_createPlan_denies403_whenRoleAdmin()},
 *           {@link #ac_security_createPlan_denies403_whenUnauthenticated()},
 *           {@link #ac_security_createPlan_allows201_whenRoleSuperAdmin()}</td></tr>
 *   <tr><td>Requiert ROLE_SUPER_ADMIN — GET /plans</td>
 *       <td>{@link #ac_security_listPlans_denies403_whenRoleAdmin()},
 *           {@link #ac_security_listPlans_allows200_whenRoleSuperAdmin()}</td></tr>
 *   <tr><td>Requiert ROLE_SUPER_ADMIN — GET /plans/{planId}</td>
 *       <td>{@link #ac_security_getPlan_denies403_whenRoleAdmin()}</td></tr>
 *   <tr><td>Requiert ROLE_SUPER_ADMIN — PUT /plans/{planId}/modules</td>
 *       <td>{@link #ac_security_replaceModules_denies403_whenRoleAdmin()},
 *           {@link #ac_security_replaceModules_allows200_whenRoleSuperAdmin()}</td></tr>
 *   <tr><td>Requiert ROLE_SUPER_ADMIN — POST /plans/{planId}/modules/{moduleId}</td>
 *       <td>{@link #ac_security_addModule_denies403_whenRoleAdmin()}</td></tr>
 *   <tr><td>Requiert ROLE_SUPER_ADMIN — GET /plans/{planId}/modules</td>
 *       <td>{@link #ac_security_getModules_denies403_whenRoleAdmin()}</td></tr>
 *   <tr><td>Entité Plan avec association M-N modules ; POST crée un plan sans module</td>
 *       <td>{@link #ac_create_persistsPlan_withEmptyModuleList()}</td></tr>
 *   <tr><td>GET /plans liste tous les plans</td>
 *       <td>{@link #ac_list_returnsAllPlans()}</td></tr>
 *   <tr><td>GET /plans/{planId} — 200 avec modules, 404 si inconnu</td>
 *       <td>{@link #ac_get_returns200WithModules_whenPlanExists()},
 *           {@link #ac_get_returns404_whenPlanUnknown()}</td></tr>
 *   <tr><td>PUT remplacement complet persiste exactement l'ensemble donné, y compris []</td>
 *       <td>{@link #ac_replaceModules_persistsExactSet()},
 *           {@link #ac_replaceModules_clearsToEmpty_whenEmptyListGiven()}</td></tr>
 *   <tr><td>PUT — 404 si plan inconnu</td>
 *       <td>{@link #ac_replaceModules_returns404_whenPlanUnknown()}</td></tr>
 *   <tr><td>PUT — 400 si module inconnu du registre</td>
 *       <td>{@link #ac_replaceModules_returns400_whenModuleUnregistered()}</td></tr>
 *   <tr><td>POST ajout unitaire — ajoute un module</td>
 *       <td>{@link #ac_addModule_addsModuleToPlan()}</td></tr>
 *   <tr><td>POST ajout unitaire — idempotent en cas de ré-ajout</td>
 *       <td>{@link #ac_addModule_isIdempotent_onReAdd()}</td></tr>
 *   <tr><td>POST — 404 si plan inconnu</td>
 *       <td>{@link #ac_addModule_returns404_whenPlanUnknown()}</td></tr>
 *   <tr><td>POST — 400 si module inconnu du registre</td>
 *       <td>{@link #ac_addModule_returns400_whenModuleUnregistered()}</td></tr>
 *   <tr><td>GET /plans/{planId}/modules — reflète l'état courant après mutations</td>
 *       <td>{@link #ac_getModules_reflectsCurrentStateAfterMutations()}</td></tr>
 * </table>
 */
@Import(SuperAdminPlanIntegrationTest.TestModuleConfig.class)
class SuperAdminPlanIntegrationTest extends AbstractIntegrationTest {

    private static final String MODULE_ID = "plan-it-test-module";
    private static final String ENDPOINT = "/superadmin/plans";

    @Autowired
    private PlanService planService;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Full Spring context + real Spring Security filter chain (springSecurity()) — unlike
        // the service-level proxy calls below, this exercises RBAC through an actual HTTP
        // round-trip, not just the @PreAuthorize AOP proxy called directly.
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        planRepository.deleteAll();
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // Security — RBAC porté par le service (proxy direct)
    // ----------------------------------------------------------------

    @Test
    void ac_security_createPlan_denies403_whenRoleAdmin() {
        setAuthentication("ROLE_ADMIN");
        final CreatePlanRequest request = new CreatePlanRequest("rbac-it-plan");

        assertThatThrownBy(() -> planService.createPlan(request)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ac_security_createPlan_allows201_whenRoleSuperAdmin() {
        setAuthentication("ROLE_SUPER_ADMIN");

        final PlanDto result = planService.createPlan(new CreatePlanRequest("rbac-it-plan-allowed"));

        assertThat(result).isNotNull();
    }

    @Test
    void ac_security_listPlans_denies403_whenRoleAdmin() {
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() -> planService.listPlans()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ac_security_listPlans_allows200_whenRoleSuperAdmin() {
        setAuthentication("ROLE_SUPER_ADMIN");

        assertThat(planService.listPlans()).isNotNull();
    }

    @Test
    void ac_security_getPlan_denies403_whenRoleAdmin() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long planId = planService.createPlan(new CreatePlanRequest("rbac-it-get-plan")).id();
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() -> planService.getPlan(planId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ac_security_replaceModules_denies403_whenRoleAdmin() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long planId = planService.createPlan(new CreatePlanRequest("rbac-it-replace-plan")).id();
        setAuthentication("ROLE_ADMIN");
        final List<String> moduleIds = List.of(MODULE_ID);

        assertThatThrownBy(() -> planService.replaceModules(planId, moduleIds))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ac_security_replaceModules_allows200_whenRoleSuperAdmin() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long planId = planService.createPlan(new CreatePlanRequest("rbac-it-replace-plan-allowed")).id();

        final PlanModulesResponse result = planService.replaceModules(planId, List.of(MODULE_ID));

        assertThat(result.moduleIds()).containsExactly(MODULE_ID);
    }

    @Test
    void ac_security_addModule_denies403_whenRoleAdmin() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long planId = planService.createPlan(new CreatePlanRequest("rbac-it-add-plan")).id();
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() -> planService.addModule(planId, MODULE_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ac_security_getModules_denies403_whenRoleAdmin() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long planId = planService.createPlan(new CreatePlanRequest("rbac-it-modules-plan")).id();
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() -> planService.getModules(planId)).isInstanceOf(AccessDeniedException.class);
    }

    // ----------------------------------------------------------------
    // Security — bout-en-bout HTTP
    // ----------------------------------------------------------------

    @Test
    void ac_security_createPlan_denies403_whenUnauthenticated() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"http-it-unauth\"}"))
                .andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------------
    // Création — POST /superadmin/plans
    // ----------------------------------------------------------------

    @Test
    void ac_create_persistsPlan_withEmptyModuleList() {
        setAuthentication("ROLE_SUPER_ADMIN");

        final PlanDto result = planService.createPlan(new CreatePlanRequest("Starter"));

        assertThat(result.moduleIds()).isEmpty();
        final Plan persisted = planRepository.findById(result.id()).orElseThrow();
        assertThat(persisted.getName()).isEqualTo("Starter");
        // moduleIds is a LAZY @ElementCollection (open-in-view disabled) — read it through the
        // @Transactional service method, not directly off a detached entity fetched here.
        assertThat(planService.getModules(result.id()).moduleIds()).isEmpty();
    }

    // ----------------------------------------------------------------
    // Liste — GET /superadmin/plans
    // ----------------------------------------------------------------

    @Test
    void ac_list_returnsAllPlans() {
        setAuthentication("ROLE_SUPER_ADMIN");
        planService.createPlan(new CreatePlanRequest("list-it-plan-a"));
        planService.createPlan(new CreatePlanRequest("list-it-plan-b"));

        final List<PlanDto> result = planService.listPlans();

        assertThat(result).extracting(PlanDto::name).contains("list-it-plan-a", "list-it-plan-b");
    }

    // ----------------------------------------------------------------
    // Détail — GET /superadmin/plans/{planId}
    // ----------------------------------------------------------------

    @Test
    void ac_get_returns200WithModules_whenPlanExists() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long planId = planService.createPlan(new CreatePlanRequest("get-it-plan")).id();
        planService.addModule(planId, MODULE_ID);

        final PlanDto result = planService.getPlan(planId);

        assertThat(result.moduleIds()).containsExactly(MODULE_ID);
    }

    @Test
    void ac_get_returns404_whenPlanUnknown() {
        setAuthentication("ROLE_SUPER_ADMIN");

        assertThatThrownBy(() -> planService.getPlan(999_999L)).isInstanceOf(PlanNotFoundException.class);
    }

    // ----------------------------------------------------------------
    // PUT /superadmin/plans/{planId}/modules — remplacement complet
    // ----------------------------------------------------------------

    @Test
    void ac_replaceModules_persistsExactSet() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long planId = planService.createPlan(new CreatePlanRequest("replace-it-plan")).id();

        planService.replaceModules(planId, List.of(MODULE_ID));

        assertThat(planService.getModules(planId).moduleIds()).containsExactly(MODULE_ID);
    }

    @Test
    void ac_replaceModules_clearsToEmpty_whenEmptyListGiven() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long planId = planService.createPlan(new CreatePlanRequest("replace-clear-it-plan")).id();
        planService.addModule(planId, MODULE_ID);

        final PlanModulesResponse result = planService.replaceModules(planId, List.of());

        assertThat(result.moduleIds()).isEmpty();
        assertThat(planService.getModules(planId).moduleIds()).isEmpty();
    }

    @Test
    void ac_replaceModules_returns404_whenPlanUnknown() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final List<String> moduleIds = List.of(MODULE_ID);

        assertThatThrownBy(() -> planService.replaceModules(999_999L, moduleIds))
                .isInstanceOf(PlanNotFoundException.class);
    }

    @Test
    void ac_replaceModules_returns400_whenModuleUnregistered() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long planId = planService.createPlan(new CreatePlanRequest("replace-bad-module-it-plan")).id();
        final List<String> moduleIds = List.of("ghost-module");

        assertThatThrownBy(() -> planService.replaceModules(planId, moduleIds))
                .isInstanceOf(UnknownModuleIdException.class);
        assertThat(planService.getModules(planId).moduleIds()).isEmpty();
    }

    // ----------------------------------------------------------------
    // POST /superadmin/plans/{planId}/modules/{moduleId} — ajout unitaire
    // ----------------------------------------------------------------

    @Test
    void ac_addModule_addsModuleToPlan() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long planId = planService.createPlan(new CreatePlanRequest("add-it-plan")).id();

        final PlanModulesResponse result = planService.addModule(planId, MODULE_ID);

        assertThat(result.moduleIds()).containsExactly(MODULE_ID);
    }

    @Test
    void ac_addModule_isIdempotent_onReAdd() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long planId = planService.createPlan(new CreatePlanRequest("add-idempotent-it-plan")).id();
        planService.addModule(planId, MODULE_ID);

        final PlanModulesResponse result = planService.addModule(planId, MODULE_ID);

        assertThat(result.moduleIds()).containsExactly(MODULE_ID);
        assertThat(planService.getModules(planId).moduleIds()).hasSize(1);
    }

    @Test
    void ac_addModule_returns404_whenPlanUnknown() {
        setAuthentication("ROLE_SUPER_ADMIN");

        assertThatThrownBy(() -> planService.addModule(999_999L, MODULE_ID))
                .isInstanceOf(PlanNotFoundException.class);
    }

    @Test
    void ac_addModule_returns400_whenModuleUnregistered() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long planId = planService.createPlan(new CreatePlanRequest("add-bad-module-it-plan")).id();

        assertThatThrownBy(() -> planService.addModule(planId, "ghost-module"))
                .isInstanceOf(UnknownModuleIdException.class);
        assertThat(planService.getModules(planId).moduleIds()).isEmpty();
    }

    // ----------------------------------------------------------------
    // GET /superadmin/plans/{planId}/modules
    // ----------------------------------------------------------------

    @Test
    void ac_getModules_reflectsCurrentStateAfterMutations() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long planId = planService.createPlan(new CreatePlanRequest("modules-it-plan")).id();

        assertThat(planService.getModules(planId).moduleIds()).isEmpty();

        planService.addModule(planId, MODULE_ID);
        assertThat(planService.getModules(planId).moduleIds()).containsExactly(MODULE_ID);

        planService.replaceModules(planId, List.of());
        assertThat(planService.getModules(planId).moduleIds()).isEmpty();
    }

    // ----------------------------------------------------------------
    // HTTP end-to-end — happy path complet à travers le contrôleur réel
    // ----------------------------------------------------------------

    @Test
    void ac_http_fullLifecycle_throughRealController() throws Exception {
        final String createResponse = mockMvc.perform(post(ENDPOINT)
                        .with(user("http-it-super-admin").authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"http-it-plan\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.moduleIds").isEmpty())
                .andReturn().getResponse().getContentAsString();

        final Long planId = Long.valueOf(createResponse.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(put(ENDPOINT + "/{planId}/modules", planId)
                        .with(user("http-it-super-admin").authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moduleIds\":[\"" + MODULE_ID + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleIds", hasSize(1)));

        mockMvc.perform(get(ENDPOINT + "/{planId}/modules", planId)
                        .with(user("http-it-super-admin").authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleIds[0]").value(MODULE_ID));

        mockMvc.perform(get(ENDPOINT + "/{planId}/modules", planId)
                        .with(user("http-it-role-admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static void setAuthentication(final String role) {
        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "plan-it-principal", null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Simule un repo module externe : déclaration d'un {@link PivotModule} par {@code @Bean} —
     * même pattern que {@code AdminModuleActivationIntegrationTest}/{@code ModuleRegistryIntegrationTest}.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestModuleConfig {

        @Bean
        PivotModule planItTestModule() {
            return new PivotModule() {
                @Override
                public String getId() {
                    return MODULE_ID;
                }

                @Override
                public String getName() {
                    return "Module de test plan IT";
                }

                @Override
                public String getVersion() {
                    return "1.0.0";
                }

                @Override
                public String getDescription() {
                    return "Module de test";
                }

                @Override
                public boolean isEnabled(final TenantContext ctx) {
                    return true;
                }
            };
        }
    }
}
