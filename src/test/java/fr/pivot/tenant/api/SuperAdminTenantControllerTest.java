package fr.pivot.tenant.api;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests unitaires (dispatch HTTP complet via MockMvc standalone, sans contexte Spring ni
 * proxy de sécurité) pour {@link SuperAdminTenantController} — US06.2.3.
 *
 * <p>Vérifie : liaison des paramètres de requête (filtres + pagination) vers le service,
 * forme JSON de l'enveloppe {@link TenantPageResponse} conforme au contrat AC
 * ({@code content, totalElements, totalPages, number, size}), et tri par défaut
 * {@code createdAt DESC} / taille 20 quand aucun paramètre de pagination n'est fourni.
 *
 * <p>Le RBAC ({@code @PreAuthorize} porté par {@link SuperAdminTenantService}) n'est pas
 * exercé ici (service mocké, hors proxy Spring Security) — couvert par
 * {@code SuperAdminTenantIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class SuperAdminTenantControllerTest {

    private static final String ENDPOINT = "/superadmin/tenants";

    @Mock
    private SuperAdminTenantService superAdminTenantService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        final SuperAdminTenantController controller = new SuperAdminTenantController(superAdminTenantService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void ac_list_shouldReturn200WithPageEnvelope_whenCalledWithNoParams() throws Exception {
        final TenantSummaryDto dto = new TenantSummaryDto(
                1L, "acme", "Acme Corp", "SAAS", "SAAS", true, 3L, Instant.parse("2026-01-01T00:00:00Z"));
        final Page<TenantSummaryDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1);
        when(superAdminTenantService.listTenants(isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get(ENDPOINT).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("acme"))
                .andExpect(jsonPath("$.content[0].isActive").value(true))
                .andExpect(jsonPath("$.content[0].userCount").value(3))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void ac_list_shouldForwardAllFourFilters_whenQueryParamsProvided() throws Exception {
        final Page<TenantSummaryDto> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(superAdminTenantService.listTenants(
                eq("acme"), eq(true), eq("ENTERPRISE"), eq("SAAS"), any(Pageable.class)))
                .thenReturn(emptyPage);

        mockMvc.perform(get(ENDPOINT)
                        .param("name", "acme")
                        .param("is_active", "true")
                        .param("plan", "ENTERPRISE")
                        .param("auth_mode", "SAAS")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void ac_list_shouldApplyDefaultPageableOfSize20SortedByCreatedAtDesc_whenNoPaginationParamsGiven() throws Exception {
        final Page<TenantSummaryDto> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        final var pageableCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        when(superAdminTenantService.listTenants(isNull(), isNull(), isNull(), isNull(), pageableCaptor.capture()))
                .thenReturn(emptyPage);

        mockMvc.perform(get(ENDPOINT).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        final Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageSize()).isEqualTo(20);
        assertThat(captured.getPageNumber()).isZero();
        assertThat(captured.getSort().getOrderFor("createdAt")).isNotNull();
        assertThat(captured.getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    @Test
    void ac_list_shouldHonorExplicitPageAndSizeParams() throws Exception {
        final Page<TenantSummaryDto> emptyPage = new PageImpl<>(List.of(), PageRequest.of(2, 5), 0);
        when(superAdminTenantService.listTenants(isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(emptyPage);

        mockMvc.perform(get(ENDPOINT)
                        .param("page", "2")
                        .param("size", "5")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.size").value(5));
    }
}
