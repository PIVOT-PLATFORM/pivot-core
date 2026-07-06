package fr.pivot.plan.api;

import java.time.Instant;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests unitaires (dispatch HTTP complet via MockMvc standalone, sans contexte Spring) pour
 * {@link SuperAdminPlanController} — US03.3.1 « SUPER_ADMIN définit modules disponibles par
 * plan ».
 *
 * <p>Vérifie : délégation correcte au service mocké, forme JSON des réponses, validation bean
 * (400), mapping exception → statut (400/404/409). Le RBAC ({@code @PreAuthorize} porté par
 * {@link PlanService}) n'est pas exercé ici (service mocké, hors proxy Spring Security) — couvert
 * par {@link SuperAdminPlanIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class SuperAdminPlanControllerTest {

    private static final String ENDPOINT = "/superadmin/plans";

    @Mock
    private PlanService planService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        final SuperAdminPlanController controller = new SuperAdminPlanController(planService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ----------------------------------------------------------------
    // POST /superadmin/plans
    // ----------------------------------------------------------------

    @Test
    void create_returns201_onValidPayload() throws Exception {
        when(planService.createPlan(any())).thenReturn(
                new PlanDto(1L, "Starter", List.of(), Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Starter\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Starter"))
                .andExpect(jsonPath("$.moduleIds").isArray())
                .andExpect(jsonPath("$.moduleIds").isEmpty());
    }

    @Test
    void create_returns400_onBlankName() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(planService, never()).createPlan(any());
    }

    @Test
    void create_returns409_onDuplicateName() throws Exception {
        when(planService.createPlan(any())).thenThrow(new PlanNameAlreadyExistsException("Starter"));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Starter\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PLAN_NAME_ALREADY_EXISTS"));
    }

    // ----------------------------------------------------------------
    // GET /superadmin/plans
    // ----------------------------------------------------------------

    @Test
    void list_returns200_withAllPlans() throws Exception {
        when(planService.listPlans()).thenReturn(List.of(
                new PlanDto(1L, "Starter", List.of(), Instant.parse("2026-01-01T00:00:00Z")),
                new PlanDto(2L, "Pro", List.of("whiteboard"), Instant.parse("2026-01-02T00:00:00Z"))));

        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("Starter"))
                .andExpect(jsonPath("$[1].moduleIds[0]").value("whiteboard"));
    }

    // ----------------------------------------------------------------
    // GET /superadmin/plans/{planId}
    // ----------------------------------------------------------------

    @Test
    void get_returns200_withPlanAndModules() throws Exception {
        when(planService.getPlan(7L)).thenReturn(
                new PlanDto(7L, "Pro", List.of("whiteboard"), Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(get(ENDPOINT + "/{planId}", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.moduleIds[0]").value("whiteboard"));
    }

    @Test
    void get_returns404_whenPlanNotFound() throws Exception {
        when(planService.getPlan(99L)).thenThrow(new PlanNotFoundException(99L));

        mockMvc.perform(get(ENDPOINT + "/{planId}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAN_NOT_FOUND"));
    }

    // ----------------------------------------------------------------
    // PUT /superadmin/plans/{planId}/modules
    // ----------------------------------------------------------------

    @Test
    void replaceModules_returns200_withUpdatedList() throws Exception {
        when(planService.replaceModules(eq(7L), any())).thenReturn(new PlanModulesResponse(List.of("whiteboard")));

        mockMvc.perform(put(ENDPOINT + "/{planId}/modules", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moduleIds\":[\"whiteboard\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleIds[0]").value("whiteboard"));
    }

    @Test
    void replaceModules_returns200_withEmptyList_whenClearingModules() throws Exception {
        when(planService.replaceModules(eq(7L), any())).thenReturn(new PlanModulesResponse(List.of()));

        mockMvc.perform(put(ENDPOINT + "/{planId}/modules", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moduleIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleIds").isEmpty());
    }

    @Test
    void replaceModules_returns400_whenModuleIdsMissing() throws Exception {
        mockMvc.perform(put(ENDPOINT + "/{planId}/modules", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(planService, never()).replaceModules(anyLong(), any());
    }

    @Test
    void replaceModules_returns400_whenUnknownModuleId() throws Exception {
        when(planService.replaceModules(eq(7L), any())).thenThrow(new UnknownModuleIdException("ghost"));

        mockMvc.perform(put(ENDPOINT + "/{planId}/modules", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moduleIds\":[\"ghost\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNKNOWN_MODULE_ID"));
    }

    @Test
    void replaceModules_returns404_whenPlanNotFound() throws Exception {
        when(planService.replaceModules(eq(99L), any())).thenThrow(new PlanNotFoundException(99L));

        mockMvc.perform(put(ENDPOINT + "/{planId}/modules", 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moduleIds\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAN_NOT_FOUND"));
    }

    // ----------------------------------------------------------------
    // POST /superadmin/plans/{planId}/modules/{moduleId}
    // ----------------------------------------------------------------

    @Test
    void addModule_returns200_withUpdatedList() throws Exception {
        when(planService.addModule(7L, "whiteboard")).thenReturn(new PlanModulesResponse(List.of("whiteboard")));

        mockMvc.perform(post(ENDPOINT + "/{planId}/modules/{moduleId}", 7L, "whiteboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleIds[0]").value("whiteboard"));
    }

    @Test
    void addModule_returns400_whenUnknownModuleId() throws Exception {
        when(planService.addModule(7L, "ghost")).thenThrow(new UnknownModuleIdException("ghost"));

        mockMvc.perform(post(ENDPOINT + "/{planId}/modules/{moduleId}", 7L, "ghost"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNKNOWN_MODULE_ID"));
    }

    @Test
    void addModule_returns404_whenPlanNotFound() throws Exception {
        when(planService.addModule(99L, "whiteboard")).thenThrow(new PlanNotFoundException(99L));

        mockMvc.perform(post(ENDPOINT + "/{planId}/modules/{moduleId}", 99L, "whiteboard"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAN_NOT_FOUND"));
    }

    // ----------------------------------------------------------------
    // GET /superadmin/plans/{planId}/modules
    // ----------------------------------------------------------------

    @Test
    void getModules_returns200_withCurrentList() throws Exception {
        when(planService.getModules(7L)).thenReturn(new PlanModulesResponse(List.of("whiteboard", "roadmap")));

        mockMvc.perform(get(ENDPOINT + "/{planId}/modules", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleIds", Matchers.hasSize(2)));
    }

    @Test
    void getModules_returns404_whenPlanNotFound() throws Exception {
        when(planService.getModules(99L)).thenThrow(new PlanNotFoundException(99L));

        mockMvc.perform(get(ENDPOINT + "/{planId}/modules", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAN_NOT_FOUND"));
    }
}
