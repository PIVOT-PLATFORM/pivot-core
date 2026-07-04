package fr.pivot.tenant.api;

import fr.pivot.AbstractIntegrationTest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests d'intégration (PostgreSQL via Testcontainers, contexte Spring réel) pour
 * {@code GET /api/superadmin/tenants} — traçabilité US06.2.3.
 *
 * <p>Exerce {@link SuperAdminTenantService} au travers du proxy Spring Method Security réel
 * ({@code @EnableMethodSecurity} dans {@code SecurityConfig}), même motif que
 * {@code AdminModuleActivationIntegrationTest} : {@code @PreAuthorize} n'est significatif
 * que si le bean est résolu via {@code @Autowired} (proxifié), jamais instancié directement.
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
 * </table>
 */
class SuperAdminTenantIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SuperAdminTenantService superAdminTenantService;

    @Autowired
    private TenantRepository tenantRepository;

    private Tenant pivotSaas;

    @BeforeEach
    void setUp() {
        pivotSaas = tenantRepository.findBySlug("pivot-saas").orElseThrow();
    }

    @AfterEach
    void tearDown() {
        // IT-created tenants must not leak into other test methods of this class (no per-tenant
        // isolation query here by design — the endpoint is deliberately cross-tenant) nor into
        // other IT classes reusing the same Testcontainers instance across the module.
        tenantRepository.findAll().stream()
                .filter(tenant -> !tenant.getId().equals(pivotSaas.getId()))
                .forEach(tenantRepository::delete);
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
    }

    @Test
    void ac_security_allowsAccess_whenCallerIsSuperAdmin() {
        setAuthentication("ROLE_SUPER_ADMIN");

        final Page<TenantSummaryDto> result = superAdminTenantService.listTenants(
                null, null, null, null, PageRequest.of(0, 20));

        assertThat(result).isNotNull();
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

    private void setAuthentication(final String role) {
        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "super-admin-it", null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
