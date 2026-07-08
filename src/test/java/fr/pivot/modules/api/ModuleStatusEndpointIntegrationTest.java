package fr.pivot.modules.api;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.entity.User;
import fr.pivot.core.modules.ModuleActivationRepository;
import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.PivotModule;
import fr.pivot.core.tenant.TenantContext;
import fr.pivot.modules.registry.ModuleStatusDto;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests d'intégration (PostgreSQL via Testcontainers) pour
 * {@code GET /api/modules/{id}/status} — traçabilité EN03.2 / US03.2.2.
 *
 * <p>Exerce la chaîne complète : {@link ModuleController} →
 * {@link fr.pivot.core.modules.cache.ModuleActivationCacheService} (cache Redis, EN03.3) →
 * {@link ModuleActivationService} (persistance réelle Flyway) et confirme la sémantique HTTP
 * documentée dans {@link ModuleStatusDto} : 200/enabled=true, 200/enabled=false, 404 module
 * inconnu. Le cache Redis (réel, via {@code AbstractIntegrationTest}) est vidé après chaque
 * test pour préserver l'isolation entre méthodes (voir {@link #tearDown()}).
 */
@Import(ModuleStatusEndpointIntegrationTest.TestModuleConfig.class)
class ModuleStatusEndpointIntegrationTest extends AbstractIntegrationTest {

    private static final String MODULE_ID = "status-it-module";

    @Autowired
    private ModuleController controller;

    @Autowired
    private ModuleActivationService activationService;

    @Autowired
    private ModuleActivationRepository activationRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.findBySlug("pivot-saas").orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        activationRepository.deleteAll();
        // Le cache Redis (EN03.3, désormais branché dans ModuleController.getModuleStatus)
        // survit à la purge BDD ci-dessus — sans ce nettoyage, un test activant le module
        // laisse une entrée "enabled=true" que les tests suivants de cette classe liraient
        // à tort (le cache n'a pas de notion de rollback transactionnel comme PostgreSQL).
        final var keys = redisTemplate.keys("module:status:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        SecurityContextHolder.clearContext();
    }

    @Test
    void status_shouldReturn200Disabled_whenModuleRegisteredButNoActivationRow() {
        setAuthentication(tenantId, "ROLE_USER");

        final ResponseEntity<ModuleStatusDto> response = controller.getModuleStatus(MODULE_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().enabled()).isFalse();
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void status_shouldReturn200Enabled_afterActivationPersisted() {
        activationService.activate(tenantId, MODULE_ID);
        setAuthentication(tenantId, "ROLE_ADMIN");

        final ResponseEntity<ModuleStatusDto> response = controller.getModuleStatus(MODULE_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().enabled()).isTrue();
    }

    @Test
    void status_shouldReturn200Disabled_afterActivateThenDeactivate() {
        activationService.activate(tenantId, MODULE_ID);
        activationService.deactivate(tenantId, MODULE_ID);
        setAuthentication(tenantId, "ROLE_USER");

        final ResponseEntity<ModuleStatusDto> response = controller.getModuleStatus(MODULE_ID);

        assertThat(response.getBody().enabled()).isFalse();
    }

    @Test
    void status_shouldThrow_whenModuleIdNotRegistered() {
        setAuthentication(tenantId, "ROLE_USER");

        assertThatThrownBy(() -> controller.getModuleStatus("this-module-does-not-exist"))
                .isInstanceOf(fr.pivot.core.modules.UnknownModuleException.class);
    }

    /**
     * Sécurité — isolation tenant : le statut résolu ne dépend que du tenant porté par le
     * token authentifié courant (via {@code User.getTenant()}), jamais d'un paramètre de
     * requête ou d'un body (le endpoint n'en accepte aucun). Deux tenants distincts avec le
     * même module activé pour un seul des deux ne peuvent pas se "voir" mutuellement.
     */
    @Test
    void status_shouldIsolateActivationStatePerTenant() {
        final Tenant otherTenant = new Tenant();
        otherTenant.setName("Other Tenant IT");
        otherTenant.setSlug("status-it-other-tenant");
        final Tenant savedOtherTenant = tenantRepository.save(otherTenant);

        activationService.activate(tenantId, MODULE_ID);
        // otherTenant never activates the module.

        setAuthentication(savedOtherTenant.getId(), "ROLE_USER");
        final ResponseEntity<ModuleStatusDto> otherResponse = controller.getModuleStatus(MODULE_ID);
        assertThat(otherResponse.getBody().enabled()).isFalse();

        setAuthentication(tenantId, "ROLE_USER");
        final ResponseEntity<ModuleStatusDto> ownResponse = controller.getModuleStatus(MODULE_ID);
        assertThat(ownResponse.getBody().enabled()).isTrue();
    }

    private void setAuthentication(final Long forTenantId, final String role) {
        final Tenant tenant = tenantRepository.findById(forTenantId).orElseThrow();
        final User user = new User();
        user.setRole(role);
        user.setTenant(tenant);

        // 3-arg constructor (with authorities) marks the token authenticated=true, matching
        // fr.pivot.config.TokenAuthenticationFilter — required for @PreAuthorize("isAuthenticated()")
        // to pass through the real Spring Security method-security proxy in this full-context TI test.
        final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                forTenantId, null, List.of(new SimpleGrantedAuthority(role)));
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Simule un repo module externe : déclaration d'un {@link PivotModule} par
     * {@code @Bean} — le registre le découvre sans modification de pivot-core.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestModuleConfig {

        @Bean
        PivotModule statusIntegrationTestModule() {
            return new PivotModule() {
                @Override
                public String getId() {
                    return MODULE_ID;
                }

                @Override
                public String getName() {
                    return "Module de test statut TI";
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
