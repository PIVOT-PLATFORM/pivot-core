package fr.pivot.modules.api;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.entity.AuditEvent;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.AuditEventRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.AuditService;
import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.PivotModule;
import fr.pivot.core.modules.UnknownModuleException;
import fr.pivot.core.tenant.TenantContext;
import fr.pivot.tenant.api.TenantNotFoundException;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests d'intégration (PostgreSQL via Testcontainers, contexte Spring réel) pour US03.3.2
 * « SUPER_ADMIN active/désactive un module par tenant (override) ».
 *
 * <p>Traçabilité :
 * <ul>
 *   <li>Security — {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} ({@link ModuleOverrideService})
 *       effectivement évalué par le proxy Spring Method Security ;</li>
 *   <li>Override en BDD, priorité sur le choix de l'admin de tenant — {@link
 *       ModuleActivationService#isEnabled} ;</li>
 *   <li>Audit — {@code ModuleOverrideSet}/{@code ModuleOverrideRemoved} avec
 *       {@code superAdminId} ({@link SuperAdminModuleOverrideController}) ;</li>
 *   <li>Retrait d'override → retour au comportement {@code module_activations} ; idempotent ;</li>
 *   <li>Isolation tenant.</li>
 * </ul>
 */
@Import(ModuleOverrideIntegrationTest.TestModuleConfig.class)
class ModuleOverrideIntegrationTest extends AbstractIntegrationTest {

    private static final String MODULE_ID = "override-it-test-module";

    @Autowired
    private ModuleOverrideService moduleOverrideService;

    @Autowired
    private ModuleActivationService moduleActivationService;

    @Autowired
    private SuperAdminModuleOverrideController controller;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    private Tenant tenantA;
    private Tenant tenantB;

    @BeforeEach
    void setUp() {
        tenantA = createTenant();
        tenantB = createTenant();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // Security : RBAC porté par le service, pas seulement le contrôleur
    // ----------------------------------------------------------------

    @Test
    void setOverride_shouldThrowAccessDenied_whenCallerIsRoleAdmin() {
        setAuthentication("ROLE_ADMIN");
        final Long tenantId = tenantA.getId();

        assertThatThrownBy(() -> moduleOverrideService.setOverride(tenantId, MODULE_ID, true))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(moduleActivationService.isEnabled(tenantId, MODULE_ID)).isFalse();
    }

    @Test
    void setOverride_shouldThrowAccessDenied_whenCallerIsRoleUser() {
        setAuthentication("ROLE_USER");
        final Long tenantId = tenantA.getId();

        assertThatThrownBy(() -> moduleOverrideService.setOverride(tenantId, MODULE_ID, true))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void removeOverride_shouldThrowAccessDenied_whenCallerIsNotSuperAdmin() {
        setAuthentication("ROLE_ADMIN");
        final Long tenantId = tenantA.getId();

        assertThatThrownBy(() -> moduleOverrideService.removeOverride(tenantId, MODULE_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ----------------------------------------------------------------
    // Override : priorité sur le choix de l'admin de tenant
    // ----------------------------------------------------------------

    @Test
    void setOverride_shouldForceEnabled_whenTenantAdminNeverActivated() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long tenantId = tenantA.getId();

        final ModuleOverrideResult result = moduleOverrideService.setOverride(tenantId, MODULE_ID, true);

        assertThat(result.overridden()).isTrue();
        assertThat(result.enabled()).isTrue();
        assertThat(moduleActivationService.isEnabled(tenantId, MODULE_ID)).isTrue();
    }

    @Test
    void setOverride_shouldForceDisabled_evenWhenTenantAdminActivatedModule() {
        final Long tenantId = tenantA.getId();
        // Tenant admin's own choice: activated.
        moduleActivationService.activate(tenantId, MODULE_ID);
        assertThat(moduleActivationService.isEnabled(tenantId, MODULE_ID)).isTrue();

        setAuthentication("ROLE_SUPER_ADMIN");
        final ModuleOverrideResult result = moduleOverrideService.setOverride(tenantId, MODULE_ID, false);

        assertThat(result.enabled()).isFalse();
        assertThat(moduleActivationService.isEnabled(tenantId, MODULE_ID)).isFalse();
    }

    @Test
    void tenantAdmin_cannotSilentlyOverrideSuperAdminDecision() {
        final Long tenantId = tenantA.getId();
        setAuthentication("ROLE_SUPER_ADMIN");
        moduleOverrideService.setOverride(tenantId, MODULE_ID, true);

        // Tenant admin explicitly tries to deactivate — persisted, but the override still wins.
        moduleActivationService.deactivate(tenantId, MODULE_ID);

        assertThat(moduleActivationService.isEnabled(tenantId, MODULE_ID)).isTrue();
    }

    // ----------------------------------------------------------------
    // Retrait de l'override : retour au comportement module_activations
    // ----------------------------------------------------------------

    @Test
    void removeOverride_shouldRevertToTenantAdminChoice() {
        final Long tenantId = tenantA.getId();
        moduleActivationService.activate(tenantId, MODULE_ID);

        setAuthentication("ROLE_SUPER_ADMIN");
        moduleOverrideService.setOverride(tenantId, MODULE_ID, false);
        assertThat(moduleActivationService.isEnabled(tenantId, MODULE_ID)).isFalse();

        final ModuleOverrideResult result = moduleOverrideService.removeOverride(tenantId, MODULE_ID);

        assertThat(result.overridden()).isFalse();
        assertThat(result.enabled()).isTrue();
        assertThat(moduleActivationService.isEnabled(tenantId, MODULE_ID)).isTrue();
    }

    @Test
    void removeOverride_shouldBeIdempotent_whenNoOverrideExisted() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long tenantId = tenantA.getId();

        final ModuleOverrideResult result = moduleOverrideService.removeOverride(tenantId, MODULE_ID);

        assertThat(result.overridden()).isFalse();
        assertThat(result.enabled()).isFalse();
    }

    // ----------------------------------------------------------------
    // Cas d'erreur
    // ----------------------------------------------------------------

    @Test
    void setOverride_shouldThrowTenantNotFound_whenTenantDoesNotExist() {
        setAuthentication("ROLE_SUPER_ADMIN");

        assertThatThrownBy(() -> moduleOverrideService.setOverride(9_999_999L, MODULE_ID, true))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void setOverride_shouldThrowUnknownModule_whenModuleNotRegistered() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long tenantId = tenantA.getId();

        assertThatThrownBy(() -> moduleOverrideService.setOverride(tenantId, "ghost-module", true))
                .isInstanceOf(UnknownModuleException.class);
    }

    // ----------------------------------------------------------------
    // Isolation tenant
    // ----------------------------------------------------------------

    @Test
    void setOverride_shouldNotAffectOtherTenant() {
        setAuthentication("ROLE_SUPER_ADMIN");
        final Long tenantIdA = tenantA.getId();
        final Long tenantIdB = tenantB.getId();

        moduleOverrideService.setOverride(tenantIdA, MODULE_ID, true);

        assertThat(moduleActivationService.isEnabled(tenantIdA, MODULE_ID)).isTrue();
        assertThat(moduleActivationService.isEnabled(tenantIdB, MODULE_ID)).isFalse();
    }

    // ----------------------------------------------------------------
    // Contrôleur bout-en-bout : audit avec superAdminId
    // ----------------------------------------------------------------

    @Test
    void controller_setOverride_shouldPersistModuleOverrideSetAuditEvent_withSuperAdminId() {
        final User superAdmin = createUser(tenantB, "ROLE_SUPER_ADMIN");
        setAuthenticationWithUserDetails(superAdmin);
        final Long tenantId = tenantA.getId();

        final ResponseEntity<ModuleOverrideResponse> response = controller.setOverride(
                tenantId, MODULE_ID, new SetModuleOverrideRequest(true),
                new MockHttpServletRequest("POST", "/api/superadmin/tenants/" + tenantId
                        + "/modules/" + MODULE_ID + "/override"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().overridden()).isTrue();
        assertThat(response.getBody().enabled()).isTrue();

        final List<AuditEvent> events = auditEventRepository.findByUserIdOrderByCreatedAtDesc(superAdmin.getId());
        final AuditEvent event = events.stream()
                .filter(e -> AuditService.MODULE_OVERRIDE_SET.equals(e.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(event.getTenant().getId()).isEqualTo(tenantId);
        // Round-trip through the JSONB column reformats the text (Postgres jsonb does not
        // preserve original whitespace/key order) — assert on the values without depending
        // on exact formatting rather than a literal substring match (same convention as
        // AdminUserIntegrationTest).
        final String meta = event.getMeta().replace(" ", "");
        assertThat(meta).contains("\"superAdminId\":" + superAdmin.getId())
                .contains("\"enabled\":true");
    }

    @Test
    void controller_removeOverride_shouldPersistModuleOverrideRemovedAuditEvent_withSuperAdminId() {
        final User superAdmin = createUser(tenantB, "ROLE_SUPER_ADMIN");
        setAuthenticationWithUserDetails(superAdmin);
        final Long tenantId = tenantA.getId();
        moduleOverrideService.setOverride(tenantId, MODULE_ID, true);

        final ResponseEntity<ModuleOverrideResponse> response = controller.removeOverride(
                tenantId, MODULE_ID,
                new MockHttpServletRequest("DELETE", "/api/superadmin/tenants/" + tenantId
                        + "/modules/" + MODULE_ID + "/override"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().overridden()).isFalse();

        final List<AuditEvent> events = auditEventRepository.findByUserIdOrderByCreatedAtDesc(superAdmin.getId());
        final AuditEvent event = events.stream()
                .filter(e -> AuditService.MODULE_OVERRIDE_REMOVED.equals(e.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(event.getTenant().getId()).isEqualTo(tenantId);
        // Round-trip through the JSONB column reformats the text — see comment above.
        final String meta = event.getMeta().replace(" ", "");
        assertThat(meta).contains("\"superAdminId\":" + superAdmin.getId());
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private Tenant createTenant() {
        final Tenant tenant = new Tenant();
        tenant.setSlug("module-override-it-" + System.nanoTime());
        tenant.setName("Module Override IT Tenant");
        return tenantRepository.save(tenant);
    }

    private User createUser(final Tenant tenant, final String role) {
        final User user = new User();
        user.setTenant(tenant);
        user.setEmail("module-override-it-" + System.nanoTime() + "@pivot.test");
        user.setRole(role);
        return userRepository.save(user);
    }

    private static void setAuthentication(final String role) {
        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "test-principal", null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void setAuthenticationWithUserDetails(final User user) {
        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "test-principal", null, List.of(new SimpleGrantedAuthority(user.getRole())));
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Simule un repo module externe : déclaration d'un {@link PivotModule} par
     * {@code @Bean} — même pattern que {@code AdminModuleActivationIntegrationTest}.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestModuleConfig {

        @Bean
        PivotModule overrideItTestModule() {
            return new PivotModule() {
                @Override
                public String getId() {
                    return MODULE_ID;
                }

                @Override
                public String getName() {
                    return "Module de test override IT";
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
                    return false;
                }
            };
        }
    }
}
