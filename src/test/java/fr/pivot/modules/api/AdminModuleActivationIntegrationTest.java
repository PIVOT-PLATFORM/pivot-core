package fr.pivot.modules.api;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.core.modules.ModuleActivation;
import fr.pivot.core.modules.ModuleActivationRepository;
import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.PivotModule;
import fr.pivot.core.tenant.TenantContext;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests d'intégration (PostgreSQL via Testcontainers, contexte Spring réel) pour
 * {@link AdminModuleActivationService}.
 *
 * <p>Traçabilité US03.1.1 / US03.1.2 :
 * <ul>
 *   <li>Security — {@code @PreAuthorize("hasRole('ADMIN')")} effectivement évalué par le
 *       proxy Spring Method Security ({@code @EnableMethodSecurity} dans
 *       {@code SecurityConfig}) : un porteur {@code ROLE_USER} est rejeté avec
 *       {@link AccessDeniedException}, jamais un simple contrôle côté contrôleur ;</li>
 *   <li>Error case — activation redondante → {@link ModuleAlreadyActiveException} ;
 *       module hors registre → {@link ModuleNotInPlanException} ;</li>
 *   <li>Isolation tenant — l'activation d'un module pour un tenant n'affecte jamais
 *       l'état d'un autre tenant.</li>
 * </ul>
 */
@Import(AdminModuleActivationIntegrationTest.TestModuleConfig.class)
class AdminModuleActivationIntegrationTest extends AbstractIntegrationTest {

    private static final String MODULE_ID = "admin-it-test-module";

    @Autowired
    private AdminModuleActivationService adminModuleActivationService;

    @Autowired
    private ModuleActivationService moduleActivationService;

    @Autowired
    private ModuleActivationRepository repository;

    @Autowired
    private TenantRepository tenantRepository;

    private Long tenantAId;
    private Long tenantBId;

    @BeforeEach
    void setUp() {
        tenantAId = tenantRepository.findBySlug("pivot-saas").orElseThrow().getId();

        final Tenant tenantB = new Tenant();
        tenantB.setSlug("admin-it-tenant-b-" + System.nanoTime());
        tenantB.setName("Tenant B (IT)");
        tenantBId = tenantRepository.save(tenantB).getId();
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll();
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // Security : RBAC porté par le service, pas seulement le contrôleur
    // ----------------------------------------------------------------

    @Test
    void activate_shouldThrowAccessDenied_whenCallerIsRoleUser() {
        setAuthentication("ROLE_USER");

        assertThatThrownBy(() -> adminModuleActivationService.activate(tenantAId, MODULE_ID))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(repository.findAllByTenantId(tenantAId)).isEmpty();
    }

    @Test
    void deactivate_shouldThrowAccessDenied_whenCallerIsRoleUser() {
        setAuthentication("ROLE_ADMIN");
        adminModuleActivationService.activate(tenantAId, MODULE_ID);
        setAuthentication("ROLE_USER");

        assertThatThrownBy(() -> adminModuleActivationService.deactivate(tenantAId, MODULE_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void activate_shouldSucceed_whenCallerIsRoleAdmin() {
        setAuthentication("ROLE_ADMIN");

        final ModuleActivation result = adminModuleActivationService.activate(tenantAId, MODULE_ID);

        assertThat(result.isEnabled()).isTrue();
        assertThat(moduleActivationService.isEnabled(tenantAId, MODULE_ID)).isTrue();
    }

    // ----------------------------------------------------------------
    // Error case : activation redondante (409)
    // ----------------------------------------------------------------

    @Test
    void activate_shouldThrowAlreadyActive_whenCalledTwice() {
        setAuthentication("ROLE_ADMIN");
        adminModuleActivationService.activate(tenantAId, MODULE_ID);

        assertThatThrownBy(() -> adminModuleActivationService.activate(tenantAId, MODULE_ID))
                .isInstanceOf(ModuleAlreadyActiveException.class);
    }

    // ----------------------------------------------------------------
    // Error case : module hors registre (403)
    // ----------------------------------------------------------------

    @Test
    void activate_shouldThrowNotInPlan_whenModuleUnregistered() {
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() -> adminModuleActivationService.activate(tenantAId, "ghost-module"))
                .isInstanceOf(ModuleNotInPlanException.class);

        assertThat(repository.findAllByTenantId(tenantAId)).isEmpty();
    }

    @Test
    void deactivate_shouldThrowNotInPlan_whenModuleUnregistered() {
        setAuthentication("ROLE_ADMIN");

        assertThatThrownBy(() -> adminModuleActivationService.deactivate(tenantAId, "ghost-module"))
                .isInstanceOf(ModuleNotInPlanException.class);
    }

    // ----------------------------------------------------------------
    // Deactivate : idempotent, jamais d'erreur si déjà inactif
    // ----------------------------------------------------------------

    @Test
    void deactivate_shouldSucceed_whenModuleWasNeverActivated() {
        setAuthentication("ROLE_ADMIN");

        final ModuleActivation result = adminModuleActivationService.deactivate(tenantAId, MODULE_ID);

        assertThat(result.isEnabled()).isFalse();
    }

    // ----------------------------------------------------------------
    // Isolation tenant
    // ----------------------------------------------------------------

    @Test
    void activate_shouldNotAffectOtherTenant() {
        setAuthentication("ROLE_ADMIN");

        adminModuleActivationService.activate(tenantAId, MODULE_ID);

        assertThat(moduleActivationService.isEnabled(tenantAId, MODULE_ID)).isTrue();
        assertThat(moduleActivationService.isEnabled(tenantBId, MODULE_ID)).isFalse();
        assertThat(repository.findAllByTenantId(tenantBId)).isEmpty();
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static void setAuthentication(final String role) {
        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "test-principal", null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Simule un repo module externe : déclaration d'un {@link PivotModule} par
     * {@code @Bean} — même pattern que {@code ModuleRegistryIntegrationTest}.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestModuleConfig {

        @Bean
        PivotModule adminItTestModule() {
            return new PivotModule() {
                @Override
                public String getId() {
                    return MODULE_ID;
                }

                @Override
                public String getName() {
                    return "Module de test admin IT";
                }

                @Override
                public String getVersion() {
                    return "1.0.0";
                }

                @Override
                public boolean isEnabled(final TenantContext ctx) {
                    return true;
                }
            };
        }
    }
}
