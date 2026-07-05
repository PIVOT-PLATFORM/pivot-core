package fr.pivot.tenant.api;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.TokenService;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests d'intégration (PostgreSQL via Testcontainers, contexte Spring réel) pour
 * {@link SuperAdminTenantService} — US06.2.2 « Super admin désactive un tenant ».
 *
 * <p>Traçabilité :
 * <ul>
 *   <li>Security — {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} effectivement évalué par
 *       le proxy Spring Method Security ({@code @EnableMethodSecurity}) ;</li>
 *   <li>Protection du tenant système — {@code pivot-saas} (slug par défaut de
 *       {@code pivot.tenant.system-tenant-slug}, seedé par {@code V2__test_seeds.sql}) ne peut
 *       pas être désactivé ;</li>
 *   <li>Révocation en masse O(1) — un token émis avant l'horodatage d'invalidation du tenant
 *       est rejeté par {@link TokenService#validate}, sans jamais toucher individuellement à
 *       l'{@code AccessToken} ; un tenant tiers non désactivé n'est jamais affecté
 *       (isolation cross-tenant).</li>
 * </ul>
 */
class SuperAdminTenantServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SuperAdminTenantService superAdminTenantService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccessTokenRepository accessTokenRepository;

    /**
     * Users created by {@link #createUser} — cleaned up in {@link #tearDown()} so this class
     * never leaves stray {@code access_tokens}/{@code users} rows behind. Other integration
     * tests (e.g. {@code TokenServiceIntegrationTest#revokeByRawToken_isNoOp_forNull}) assert
     * a globally empty {@code access_tokens} table, which would otherwise become order-dependent.
     */
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        createdUserIds.forEach(accessTokenRepository::deleteByUserId);
        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.clear();
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // RBAC — porté par le service, pas seulement le contrôleur
    // ----------------------------------------------------------------

    @Test
    void updateStatus_shouldThrowAccessDenied_whenCallerIsNotSuperAdmin() {
        final Tenant tenant = createTenant();
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() -> superAdminTenantService.updateStatus(tenant.getId(), "INACTIVE"))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(tenantRepository.findById(tenant.getId()).orElseThrow().isActive()).isTrue();
    }

    // ----------------------------------------------------------------
    // Protection du tenant système
    // ----------------------------------------------------------------

    @Test
    void updateStatus_shouldThrowSystemTenantProtected_whenTargetingSystemTenant() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Tenant systemTenant = tenantRepository.findBySlug("pivot-saas").orElseThrow();

        assertThatThrownBy(() -> superAdminTenantService.updateStatus(systemTenant.getId(), "INACTIVE"))
                .isInstanceOf(SystemTenantProtectedException.class);

        // Untouched — no invalidation timestamp, still active.
        final Tenant reloaded = tenantRepository.findById(systemTenant.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getTenantInvalidationTimestamp()).isNull();
    }

    // ----------------------------------------------------------------
    // Cas d'erreur
    // ----------------------------------------------------------------

    @Test
    void updateStatus_shouldThrowTenantNotFound_whenTenantDoesNotExist() {
        setAuthentication("ROLE_SUPER_ADMIN");

        assertThatThrownBy(() -> superAdminTenantService.updateStatus(9_999_999L, "INACTIVE"))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void updateStatus_shouldThrowUnsupportedStatus_whenStatusIsNotInactive() {
        final Tenant tenant = createTenant();
        setAuthentication("ROLE_SUPER_ADMIN");

        assertThatThrownBy(() -> superAdminTenantService.updateStatus(tenant.getId(), "ACTIVE"))
                .isInstanceOf(UnsupportedTenantStatusException.class);

        assertThat(tenantRepository.findById(tenant.getId()).orElseThrow().isActive()).isTrue();
    }

    // ----------------------------------------------------------------
    // Révocation en masse (O(1)) + isolation cross-tenant
    // ----------------------------------------------------------------

    @Test
    void updateStatus_shouldRejectTokenIssuedBeforeInvalidation_butNeverAffectOtherTenant() {
        final Tenant tenantA = createTenant();
        final User userA = createUser(tenantA);
        final String tokenBeforeDeactivation = tokenService.issue(
                userA, null, null, "ua", "127.0.0.1", AuthMethod.PASSWORD, false).rawToken();

        // A different, never-deactivated tenant — must remain wholly unaffected.
        final Tenant tenantB = createTenant();
        final User userB = createUser(tenantB);
        final String tokenForOtherTenant = tokenService.issue(
                userB, null, null, "ua", "127.0.0.1", AuthMethod.PASSWORD, false).rawToken();

        setAuthentication("ROLE_SUPER_ADMIN");
        final Instant before = Instant.now();
        final Tenant deactivated = superAdminTenantService.updateStatus(tenantA.getId(), "INACTIVE");
        final Instant after = Instant.now();

        // O(1) confirmation: a single UPDATE on the tenant row, timestamp bracketed by the call.
        assertThat(deactivated.isActive()).isFalse();
        assertThat(deactivated.getTenantInvalidationTimestamp()).isBetween(before, after);

        // Token issued before deactivation for the deactivated tenant's user → rejected.
        assertThat(tokenService.validate(tokenBeforeDeactivation)).isEmpty();

        // Token for the OTHER (non-deactivated) tenant's user → still valid — no cross-tenant impact.
        assertThat(tokenService.validate(tokenForOtherTenant)).isPresent();
    }

    @Test
    void validate_shouldAcceptToken_whenIssuedAfterTenantInvalidationTimestamp() {
        final Tenant tenant = createTenant();
        final User user = createUser(tenant);

        // Simulate a prior deactivation timestamp in the past.
        tenant.setTenantInvalidationTimestamp(Instant.now().minusSeconds(60));
        tenantRepository.saveAndFlush(tenant);

        // Newly issued token — created_at is necessarily after the invalidation timestamp.
        final String rawToken = tokenService.issue(
                user, null, null, "ua", "127.0.0.1", AuthMethod.PASSWORD, false).rawToken();

        assertThat(tokenService.validate(rawToken)).isPresent();
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private Tenant createTenant() {
        final Tenant tenant = new Tenant();
        tenant.setSlug("superadmin-it-tenant-" + System.nanoTime());
        tenant.setName("SuperAdmin IT Tenant");
        return tenantRepository.save(tenant);
    }

    private User createUser(final Tenant tenant) {
        final User user = new User();
        user.setTenant(tenant);
        user.setEmail("superadmin-it-" + System.nanoTime() + "@pivot.test");
        user.setRole("ROLE_USER");
        final User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private static void setAuthentication(final String role) {
        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "test-principal", null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
