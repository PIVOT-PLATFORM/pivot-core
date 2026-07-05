package fr.pivot.tenant.api;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.auth.repository.AuditEventRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.AuditService;
import fr.pivot.auth.service.RateLimiterService;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'intégration (PostgreSQL + Redis réels, contexte Spring réel) pour {@code GET
 * /api/superadmin/tenants} (US06.2.3), {@code POST /api/superadmin/tenants} et {@code GET
 * /api/superadmin/tenants/check-slug} (US06.2.1).
 *
 * <p>Exerce {@link SuperAdminTenantService} au travers du proxy Spring Method Security réel
 * ({@code @EnableMethodSecurity} dans {@code SecurityConfig}), même motif que
 * {@code AdminModuleActivationIntegrationTest} : {@code @PreAuthorize} n'est significatif que si
 * le bean est résolu via {@code @Autowired} (proxifié), jamais instancié directement.
 *
 * <p>Le rate limiter Redis n'est pas mocké ici (contrairement à {@code
 * SuperAdminTenantServiceTest}) — le bucket du super admin de seed est explicitement remis à
 * zéro avant/après chaque test pour rester déterministe malgré l'instance Redis partagée entre
 * classes de test.
 *
 * <table>
 *   <caption>Traçabilité AC → test</caption>
 *   <tr><th>AC</th><th>Test</th></tr>
 *   <tr><td>Requiert ROLE_SUPER_ADMIN</td>
 *       <td>{@link #ac_security_deniesAccess_whenCallerIsRoleAdmin()},
 *           {@link #ac_security_allowsAccess_whenCallerIsSuperAdmin()}</td></tr>
 *   <tr><td>Liste paginée, page size par défaut 20</td>
 *       <td>{@link #ac_pagination_returnsAllSeededTenants_onFirstPage()}</td></tr>
 *   <tr><td>Pagination page/size</td>
 *       <td>{@link #ac_pagination_honorsExplicitPageAndSize()}</td></tr>
 *   <tr><td>Tri par défaut createdAt DESC</td>
 *       <td>{@link #ac_defaultSort_ordersByCreatedAtDescending()}</td></tr>
 *   <tr><td>Filtre name</td><td>{@link #ac_filter_byNameSubstringCaseInsensitive()}</td></tr>
 *   <tr><td>Filtre is_active</td><td>{@link #ac_filter_byActiveStatus()}</td></tr>
 *   <tr><td>Filtre plan</td><td>{@link #ac_filter_byPlan()}</td></tr>
 *   <tr><td>Filtre auth_mode</td><td>{@link #ac_filter_byAuthMode()}</td></tr>
 *   <tr><td>userCount via jointure/agrégation</td>
 *       <td>{@link #ac_userCount_reflectsActualUserCountPerTenant()}</td></tr>
 *   <tr><td>Requiert ROLE_SUPER_ADMIN — bout-en-bout HTTP (pas seulement le proxy service)</td>
 *       <td>{@link #ac_security_http_deniesWith403_whenCallerHasRoleAdmin()},
 *           {@link #ac_security_http_allowsWith200_whenCallerHasRoleSuperAdmin()},
 *           {@link #ac_security_http_deniesWith403_whenCallerUnauthenticated()}</td></tr>
 *   <tr><td>Pageable.size plafonné ({@code PaginationConfig})</td>
 *       <td>{@link #ac_pageSize_isCappedAtGlobalMaximum_whenCallerRequestsExcessiveSize()}</td></tr>
 *   <tr><td>Crée un tenant avec is_active: true</td>
 *       <td>{@link #ac_create_persistsTenant_withIsActiveTrue()}</td></tr>
 *   <tr><td>Réponse retourne l'ID et une URL d'invitation</td>
 *       <td>{@link #ac_create_returnsIdAndInvitationUrl()}</td></tr>
 *   <tr><td>Audit event TenantCreated</td>
 *       <td>{@link #ac_create_logsTenantCreatedAuditEvent()}</td></tr>
 *   <tr><td>Slug unique (409 si doublon)</td>
 *       <td>{@link #ac_slugUnique_throwsConflict_onDuplicate()}</td></tr>
 *   <tr><td>Slug réservé → 422</td>
 *       <td>{@link #ac_reservedSlug_throwsUnprocessable()}</td></tr>
 *   <tr><td>Rate limit 10/heure → 429 + audit</td>
 *       <td>{@link #ac_rateLimit_after10CreationsInHour_throwsAndLogsAudit()}</td></tr>
 *   <tr><td>check-slug disponible/réservé/pris</td>
 *       <td>{@link #ac_checkSlug_available_whenFreeAndValid()},
 *           {@link #ac_checkSlug_reserved_whenSlugIsReservedWord()},
 *           {@link #ac_checkSlug_taken_whenSlugAlreadyExists()}</td></tr>
 *   <tr><td>check-slug requiert ROLE_SUPER_ADMIN</td>
 *       <td>{@link #ac_checkSlug_deniesAccess_whenCallerIsRoleAdmin()}</td></tr>
 * </table>
 */
class SuperAdminTenantIntegrationTest extends AbstractIntegrationTest {

    private static final String ENDPOINT = "/superadmin/tenants";
    private static final String IP = "127.0.0.1";
    private static final String USER_AGENT = "junit-it";

    @Autowired
    private SuperAdminTenantService superAdminTenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private Tenant pivotSaas;
    private User superAdmin;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        pivotSaas = tenantRepository.findBySlug("pivot-saas").orElseThrow();
        // Full Spring context + real Spring Security filter chain (springSecurity()) — unlike
        // the service-level tests below, this exercises RBAC through an actual HTTP round-trip
        // (ExceptionTranslationFilter turning AccessDeniedException into a real 403 response),
        // not just the @PreAuthorize AOP proxy called directly.
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        superAdmin = userRepository
                .findByTenantIdAndEmailAndDeletedAtIsNull(pivotSaas.getId(), "super_admin@pivot.test")
                .orElseThrow();
        rateLimiterService.reset(rateLimiterService.tenantCreationBucket(String.valueOf(superAdmin.getId())));
    }

    @AfterEach
    void tearDown() {
        // Tenant-scoped audit events must be deleted before their tenant (fk_audit_tenant is
        // ON DELETE RESTRICT — RGPD Art. 5.2 accountability, no cascading delete by design).
        // IT-created tenants must not leak into other test methods of this class (no per-tenant
        // isolation query here by design — the endpoint is deliberately cross-tenant) nor into
        // other IT classes reusing the same Testcontainers instance across the module.
        final List<Tenant> createdByThisTest = tenantRepository.findAll().stream()
                .filter(tenant -> !tenant.getId().equals(pivotSaas.getId()))
                .toList();
        final List<Long> createdIds = createdByThisTest.stream().map(Tenant::getId).toList();
        auditEventRepository.findAll().stream()
                .filter(event -> event.getTenant() != null && createdIds.contains(event.getTenant().getId()))
                .forEach(auditEventRepository::delete);
        tenantRepository.deleteAll(createdByThisTest);

        rateLimiterService.reset(rateLimiterService.tenantCreationBucket(String.valueOf(superAdmin.getId())));
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // Security — RBAC porté par le service, pas seulement le contrôleur
    // ----------------------------------------------------------------

    @Test
    void ac_security_deniesAccess_whenCallerIsRoleAdmin() {
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() -> superAdminTenantService.listTenants(
                null, null, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> superAdminTenantService.createTenant(
                requestFor("rbac-it-denied"), superAdmin, IP, USER_AGENT))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ac_security_allowsAccess_whenCallerIsSuperAdmin() {
        setAuthentication("ROLE_SUPER_ADMIN");

        final Page<TenantSummaryDto> result = superAdminTenantService.listTenants(
                null, null, null, null, PageRequest.of(0, 20));

        assertThat(result).isNotNull();

        final CreateTenantResponse response =
                superAdminTenantService.createTenant(requestFor("rbac-it-allowed"), superAdmin, IP, USER_AGENT);

        assertThat(response).isNotNull();
    }

    @Test
    void ac_checkSlug_deniesAccess_whenCallerIsRoleAdmin() {
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() -> superAdminTenantService.checkSlugAvailability("some-slug"))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ----------------------------------------------------------------
    // Security — bout-en-bout HTTP (dispatch réel via MockMvc + filtre Spring Security réel,
    // pas seulement le proxy Spring Method Security autour du service ci-dessus)
    // ----------------------------------------------------------------

    @Test
    void ac_security_http_deniesWith403_whenCallerHasRoleAdmin() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .with(user("http-it-admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void ac_security_http_allowsWith200_whenCallerHasRoleSuperAdmin() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .with(user("http-it-super-admin")
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void ac_security_http_deniesWith403_whenCallerUnauthenticated() throws Exception {
        // No AuthenticationEntryPoint is registered in SecurityConfig (httpBasic/formLogin both
        // disabled — stateless opaque-token auth only), so Spring Security's ExceptionTranslationFilter
        // falls back to Http403ForbiddenEntryPoint for an unauthenticated request: 403, not 401.
        // This is existing, application-wide SecurityConfig behaviour, not specific to this
        // endpoint — asserted here as observed reality, not prescribed by this US.
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------------
    // Pageable.size — plafond global (PaginationConfig)
    // ----------------------------------------------------------------

    @Test
    void ac_pageSize_isCappedAtGlobalMaximum_whenCallerRequestsExcessiveSize() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .param("size", "999999")
                        .with(user("http-it-super-admin")
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    // ----------------------------------------------------------------
    // Pagination
    // ----------------------------------------------------------------

    @Test
    void ac_pagination_returnsAllSeededTenants_onFirstPage() {
        setAuthentication("ROLE_SUPER_ADMIN");

        final Page<TenantSummaryDto> result = superAdminTenantService.listTenants(
                null, null, null, null, PageRequest.of(0, 20));

        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getNumber()).isZero();
        assertThat(result.getContent()).extracting(TenantSummaryDto::slug).contains("pivot-saas");
    }

    @Test
    void ac_pagination_honorsExplicitPageAndSize() {
        createTenant("page-it-tenant-a", "Page IT Tenant A", "SAAS", "SAAS", true);
        createTenant("page-it-tenant-b", "Page IT Tenant B", "SAAS", "SAAS", true);
        setAuthentication("ROLE_SUPER_ADMIN");

        final Page<TenantSummaryDto> firstPage = superAdminTenantService.listTenants(
                null, null, null, null, PageRequest.of(0, 1));

        assertThat(firstPage.getContent()).hasSize(1);
        assertThat(firstPage.getSize()).isEqualTo(1);
        assertThat(firstPage.getTotalElements()).isGreaterThanOrEqualTo(3);
    }

    // ----------------------------------------------------------------
    // Tri par défaut
    // ----------------------------------------------------------------

    @Test
    void ac_defaultSort_ordersByCreatedAtDescending() throws InterruptedException {
        final Tenant older = createTenant("sort-it-older", "Sort IT Older", "SAAS", "SAAS", true);
        // createdAt has no public setter (immutable audit column) — a short real delay
        // guarantees a distinct, strictly later Instant.now() for "newer" without relying on
        // clock resolution, avoiding flakiness in the DESC-ordering assertion below.
        Thread.sleep(5);
        final Tenant newer = createTenant("sort-it-newer", "Sort IT Newer", "SAAS", "SAAS", true);
        setAuthentication("ROLE_SUPER_ADMIN");

        // Sort order mirrors the controller's @PageableDefault(sort = "createdAt", direction = DESC).
        final Page<TenantSummaryDto> result = superAdminTenantService.listTenants(
                null, null, null, null,
                PageRequest.of(0, 50, org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));

        final List<String> slugsInOrder = result.getContent().stream().map(TenantSummaryDto::slug).toList();
        final int newerIndex = slugsInOrder.indexOf(newer.getSlug());
        final int olderIndex = slugsInOrder.indexOf(older.getSlug());
        assertThat(newerIndex).isLessThan(olderIndex);
    }

    // ----------------------------------------------------------------
    // Filtres
    // ----------------------------------------------------------------

    @Test
    void ac_filter_byNameSubstringCaseInsensitive() {
        final Tenant created = createTenant("filter-it-name", "Zephyr Widgets", "SAAS", "SAAS", true);
        setAuthentication("ROLE_SUPER_ADMIN");

        final Page<TenantSummaryDto> result = superAdminTenantService.listTenants(
                "zephyr", null, null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(TenantSummaryDto::slug).containsExactly(created.getSlug());
    }

    @Test
    void ac_filter_byActiveStatus() {
        final Tenant created = createTenant("filter-it-inactive", "Filter IT Inactive", "SAAS", "SAAS", false);
        setAuthentication("ROLE_SUPER_ADMIN");

        final Page<TenantSummaryDto> activeResult = superAdminTenantService.listTenants(
                null, false, null, null, PageRequest.of(0, 20));

        assertThat(activeResult.getContent()).extracting(TenantSummaryDto::slug)
                .contains(created.getSlug());
        assertThat(activeResult.getContent()).allSatisfy(dto -> assertThat(dto.isActive()).isFalse());
    }

    @Test
    void ac_filter_byPlan() {
        final Tenant created = createTenant("filter-it-enterprise", "Filter IT Enterprise", "ENTERPRISE", "ENTERPRISE", true);
        setAuthentication("ROLE_SUPER_ADMIN");

        final Page<TenantSummaryDto> result = superAdminTenantService.listTenants(
                null, null, "ENTERPRISE", null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(TenantSummaryDto::slug).contains(created.getSlug());
        assertThat(result.getContent()).allSatisfy(dto -> assertThat(dto.plan()).isEqualTo("ENTERPRISE"));
    }

    @Test
    void ac_filter_byAuthMode() {
        final Tenant created = createTenant("filter-it-hybrid", "Filter IT Hybrid", "ENTERPRISE", "HYBRID", true);
        setAuthentication("ROLE_SUPER_ADMIN");

        final Page<TenantSummaryDto> result = superAdminTenantService.listTenants(
                null, null, null, "HYBRID", PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(TenantSummaryDto::slug).contains(created.getSlug());
        assertThat(result.getContent()).allSatisfy(dto -> assertThat(dto.authMode()).isEqualTo("HYBRID"));
    }

    // ----------------------------------------------------------------
    // userCount — agrégation, pas de compteur dénormalisé
    // ----------------------------------------------------------------

    @Test
    void ac_userCount_reflectsActualUserCountPerTenant() {
        setAuthentication("ROLE_SUPER_ADMIN");

        // Filter by name ("PIVOT SaaS" — see V1__schema_init.sql), not slug ("pivot-saas"):
        // TenantSpecifications#nameContains matches Tenant.name, not Tenant.slug.
        final Page<TenantSummaryDto> result = superAdminTenantService.listTenants(
                "PIVOT SaaS", null, null, null, PageRequest.of(0, 20));

        // V2__test_seeds.sql seeds exactly 5 users on tenant "pivot-saas" (id=1):
        // super_admin, admin, user, unverified, blocked — none soft-deleted.
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).slug()).isEqualTo("pivot-saas");
        assertThat(result.getContent().get(0).userCount()).isEqualTo(5L);
    }

    // ----------------------------------------------------------------
    // Création — champs, is_active, URL d'invitation, audit
    // ----------------------------------------------------------------

    @Test
    void ac_create_persistsTenant_withIsActiveTrue() {
        setAuthentication("ROLE_SUPER_ADMIN");

        final CreateTenantResponse response =
                superAdminTenantService.createTenant(requestFor("active-it-tenant"), superAdmin, IP, USER_AGENT);

        final Tenant persisted = tenantRepository.findById(response.id()).orElseThrow();
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getSlug()).isEqualTo("active-it-tenant");
        assertThat(persisted.getAuthMode()).isEqualTo("LOCAL");
        assertThat(persisted.getPlan()).isEqualTo("SAAS");
    }

    @Test
    void ac_create_returnsIdAndInvitationUrl() {
        setAuthentication("ROLE_SUPER_ADMIN");

        final CreateTenantResponse response =
                superAdminTenantService.createTenant(requestFor("invite-it-tenant"), superAdmin, IP, USER_AGENT);

        assertThat(response.id()).isNotNull();
        assertThat(response.invitationUrl()).contains("invite-it-tenant");
    }

    @Test
    void ac_create_logsTenantCreatedAuditEvent() {
        setAuthentication("ROLE_SUPER_ADMIN");

        final CreateTenantResponse response =
                superAdminTenantService.createTenant(requestFor("audit-it-tenant"), superAdmin, IP, USER_AGENT);

        final boolean found = auditEventRepository.findAll().stream()
                .anyMatch(event -> AuditService.TENANT_CREATED.equals(event.getEventType())
                        && event.getTenant() != null
                        && event.getTenant().getId().equals(response.id()));
        assertThat(found).isTrue();
    }

    // ----------------------------------------------------------------
    // Slug — unicité (409) et réservé (422)
    // ----------------------------------------------------------------

    @Test
    void ac_slugUnique_throwsConflict_onDuplicate() {
        setAuthentication("ROLE_SUPER_ADMIN");
        superAdminTenantService.createTenant(requestFor("dup-it-tenant"), superAdmin, IP, USER_AGENT);

        assertThatThrownBy(() -> superAdminTenantService.createTenant(
                requestFor("dup-it-tenant"), superAdmin, IP, USER_AGENT))
                .isInstanceOf(TenantSlugAlreadyExistsException.class);
    }

    @Test
    void ac_reservedSlug_throwsUnprocessable() {
        setAuthentication("ROLE_SUPER_ADMIN");

        assertThatThrownBy(() -> superAdminTenantService.createTenant(
                requestFor("system"), superAdmin, IP, USER_AGENT))
                .isInstanceOf(ReservedTenantSlugException.class);
    }

    // ----------------------------------------------------------------
    // Rate limit — 10/heure, 429 + audit (Redis réel)
    // ----------------------------------------------------------------

    @Test
    void ac_rateLimit_after10CreationsInHour_throwsAndLogsAudit() {
        setAuthentication("ROLE_SUPER_ADMIN");
        for (int i = 0; i < 10; i++) {
            superAdminTenantService.createTenant(requestFor("rl-it-tenant-" + i), superAdmin, IP, USER_AGENT);
        }

        assertThatThrownBy(() -> superAdminTenantService.createTenant(
                requestFor("rl-it-tenant-eleventh"), superAdmin, IP, USER_AGENT))
                .isInstanceOf(RateLimitException.class);

        final boolean rateLimitAuditLogged = auditEventRepository.findAll().stream()
                .anyMatch(event -> AuditService.TENANT_CREATION_RATE_LIMIT_EXCEEDED.equals(event.getEventType()));
        assertThat(rateLimitAuditLogged).isTrue();
    }

    // ----------------------------------------------------------------
    // check-slug
    // ----------------------------------------------------------------

    @Test
    void ac_checkSlug_available_whenFreeAndValid() {
        setAuthentication("ROLE_SUPER_ADMIN");

        final SlugAvailabilityResponse response = superAdminTenantService.checkSlugAvailability("brand-new-it-slug");

        assertThat(response.available()).isTrue();
    }

    @Test
    void ac_checkSlug_reserved_whenSlugIsReservedWord() {
        setAuthentication("ROLE_SUPER_ADMIN");

        final SlugAvailabilityResponse response = superAdminTenantService.checkSlugAvailability("auth");

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).isEqualTo("RESERVED");
    }

    @Test
    void ac_checkSlug_taken_whenSlugAlreadyExists() {
        setAuthentication("ROLE_SUPER_ADMIN");
        superAdminTenantService.createTenant(requestFor("taken-it-slug"), superAdmin, IP, USER_AGENT);

        final SlugAvailabilityResponse response = superAdminTenantService.checkSlugAvailability("taken-it-slug");

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).isEqualTo("TAKEN");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private Tenant createTenant(final String slug, final String name, final String plan,
            final String authMode, final boolean active) {
        final Tenant tenant = new Tenant();
        tenant.setSlug(slug + "-" + System.nanoTime());
        tenant.setName(name);
        tenant.setPlan(plan);
        tenant.setAuthMode(authMode);
        tenant.setActive(active);
        return tenantRepository.save(tenant);
    }

    private static CreateTenantRequest requestFor(final String slug) {
        return new CreateTenantRequest("IT Tenant " + slug, slug, "SAAS", "LOCAL");
    }

    private void setAuthentication(final String role) {
        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "super-admin-it", null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
