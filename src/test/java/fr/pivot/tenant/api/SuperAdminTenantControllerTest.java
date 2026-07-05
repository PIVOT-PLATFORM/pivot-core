package fr.pivot.tenant.api;

import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.auth.web.GlobalExceptionHandler;
import fr.pivot.config.CookieHelper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests unitaires (dispatch HTTP complet via MockMvc standalone, sans contexte Spring) pour
 * {@link SuperAdminTenantController} — US06.2.3 « Super admin liste tous les tenants » et
 * US06.2.1 « Super admin crée un tenant ».
 *
 * <p>Vérifie : liaison des paramètres de requête (filtres + pagination) vers le service, forme
 * JSON de l'enveloppe {@link TenantPageResponse}, validation bean (400), mapping exception →
 * statut (409, 422, 429 via {@link GlobalExceptionHandler}), le repli 401 quand le contexte de
 * sécurité ne porte aucun détail {@link User}, et le happy path (201/200).
 *
 * <p>Le RBAC ({@code @PreAuthorize} porté par {@link SuperAdminTenantService}) n'est pas exercé
 * ici (service mocké, hors proxy Spring Security) — couvert par
 * {@link SuperAdminTenantIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class SuperAdminTenantControllerTest {

    private static final String ENDPOINT = "/superadmin/tenants";

    @Mock
    private SuperAdminTenantService superAdminTenantService;

    @Mock
    private CookieHelper cookieHelper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        final SuperAdminTenantController controller =
                new SuperAdminTenantController(superAdminTenantService, cookieHelper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        lenient().when(cookieHelper.clientIp(any())).thenReturn("127.0.0.1");
        authenticateAsSuperAdmin();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // GET /superadmin/tenants — US06.2.3
    // ----------------------------------------------------------------

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

    // ----------------------------------------------------------------
    // POST /superadmin/tenants — US06.2.1
    // ----------------------------------------------------------------

    @Test
    void create_returns201_onValidPayload() throws Exception {
        when(superAdminTenantService.createTenant(any(), any(), anyString(), any()))
                .thenReturn(new CreateTenantResponse(1L, "acme-corp", "https://app.pivot.test/auth/register?tenant=acme-corp"));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.slug").value("acme-corp"))
                .andExpect(jsonPath("$.invitationUrl").value("https://app.pivot.test/auth/register?tenant=acme-corp"));
    }

    @Test
    void create_returns400_onBlankName() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"slug\":\"acme-corp\",\"plan\":\"SAAS\",\"authMode\":\"LOCAL\"}"))
                .andExpect(status().isBadRequest());

        verify(superAdminTenantService, never()).createTenant(any(), any(), anyString(), any());
    }

    @Test
    void create_returns400_onMalformedSlug() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"slug\":\"AB\",\"plan\":\"SAAS\",\"authMode\":\"LOCAL\"}"))
                .andExpect(status().isBadRequest());

        verify(superAdminTenantService, never()).createTenant(any(), any(), anyString(), any());
    }

    @Test
    void create_returns400_onUnknownAuthMode() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"slug\":\"acme-corp\",\"plan\":\"SAAS\",\"authMode\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_returns409_onDuplicateSlug() throws Exception {
        when(superAdminTenantService.createTenant(any(), any(), anyString(), any()))
                .thenThrow(new TenantSlugAlreadyExistsException("acme-corp"));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TENANT_SLUG_ALREADY_EXISTS"));
    }

    @Test
    void create_returns422_onReservedSlug() throws Exception {
        when(superAdminTenantService.createTenant(any(), any(), anyString(), any()))
                .thenThrow(new ReservedTenantSlugException("admin"));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("TENANT_SLUG_RESERVED"));
    }

    @Test
    void create_returns429_withRetryAfterHeader_whenRateLimited() throws Exception {
        when(superAdminTenantService.createTenant(any(), any(), anyString(), any()))
                .thenThrow(new RateLimitException(1800L));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1800"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void create_returns401_whenSecurityContextHasNoUserDetails() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isUnauthorized());

        verify(superAdminTenantService, never()).createTenant(any(), any(), anyString(), any());
    }

    // ----------------------------------------------------------------
    // GET /superadmin/tenants/check-slug — US06.2.1
    // ----------------------------------------------------------------

    @Test
    void checkSlug_returns200_withAvailability() throws Exception {
        when(superAdminTenantService.checkSlugAvailability("brand-new")).thenReturn(SlugAvailabilityResponse.ofAvailable());

        mockMvc.perform(get(ENDPOINT + "/check-slug").param("slug", "brand-new"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    void checkSlug_returns200_withReasonWhenUnavailable() throws Exception {
        when(superAdminTenantService.checkSlugAvailability("admin")).thenReturn(SlugAvailabilityResponse.reserved());

        mockMvc.perform(get(ENDPOINT + "/check-slug").param("slug", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("RESERVED"));
    }

    @Test
    void checkSlug_returns401_whenSecurityContextHasNoUserDetails() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get(ENDPOINT + "/check-slug").param("slug", "brand-new"))
                .andExpect(status().isUnauthorized());

        verify(superAdminTenantService, never()).checkSlugAvailability(any());
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static String validPayload() {
        return "{\"name\":\"Acme Corp\",\"slug\":\"acme-corp\",\"plan\":\"SAAS\",\"authMode\":\"LOCAL\"}";
    }

    private static void authenticateAsSuperAdmin() {
        final User user = new User();
        user.setRole("ROLE_SUPER_ADMIN");
        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "super-admin-test", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
