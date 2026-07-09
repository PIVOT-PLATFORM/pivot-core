package fr.pivot.modules.api;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.entity.User;
import fr.pivot.core.modules.ModuleActivationRepository;
import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.modules.registry.ModuleDto;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests d'intégration (PostgreSQL + Redis réels via Testcontainers) pour {@code GET /api/modules}
 * (liste, EN03.4/US03.2.1) — traçabilité « Dette S2 ».
 *
 * <p><strong>Contexte du correctif</strong> : {@code GET /api/modules/{id}/status} (US03.2.2,
 * voir {@link ModuleStatusEndpointIntegrationTest}) était déjà raccordé au cache Redis EN03.3
 * depuis son introduction. {@code GET /api/modules} (cette classe), en revanche, résolvait le
 * statut de chaque module <em>catalogué</em> (propriété {@code pivot.modules.catalog}) via
 * {@link fr.pivot.core.modules.ConfiguredPivotModule#isEnabled}, qui appelait directement
 * {@link ModuleActivationService} — contournant totalement le cache alors même que celui-ci
 * existait et était livré (gap documenté dans {@code pivot-docs} depuis 2026-07-05). Ce test
 * exerce la chaîne réelle {@link ModuleController#getModules()} →
 * {@link fr.pivot.modules.registry.ModuleRegistryService} → {@code ConfiguredPivotModule} →
 * {@link fr.pivot.core.modules.cache.ModuleActivationCacheService} → Redis, pour un module
 * <em>catalogué</em> — seul mécanisme qui construit un {@code ConfiguredPivotModule} réel (les
 * autres {@code *IntegrationTest} de ce paquet déclarent leur module de test via
 * {@code @Bean PivotModule}, chemin d'auto-découverte qui ne passe jamais par cette classe).
 *
 * <p>Redis est fourni par un conteneur Testcontainers dédié (comme
 * {@link fr.pivot.core.modules.cache.ModuleActivationCacheServiceIntegrationTest}) plutôt que par
 * l'instance {@code localhost:6379} par défaut, pour ne dépendre d'aucun état externe.
 */
class ModuleListEndpointCacheIntegrationTest extends AbstractIntegrationTest {

    private static final String MODULE_ID = "list-it-cataloged-module";

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        // Cf. AbstractIntegrationTest : démarrage statique pour garantir la disponibilité
        // du conteneur avant la création du contexte Spring.
        REDIS.start();
    }

    @DynamicPropertySource
    static void configureRedisAndCatalog(final DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // pivot.modules.catalog est "[]" dans application-test.yml — les propriétés dynamiques
        // ont la priorité la plus haute et l'emportent, faisant apparaître ce module catalogué
        // (seul chemin qui construit un ConfiguredPivotModule réel, voir Javadoc de classe).
        registry.add("pivot.modules.catalog[0].id", () -> MODULE_ID);
        registry.add("pivot.modules.catalog[0].name", () -> "Module catalogué TI");
        registry.add("pivot.modules.catalog[0].version", () -> "1.0.0");
        registry.add("pivot.modules.catalog[0].description", () -> "Module de test catalogué");
    }

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
        redisTemplate.delete(cacheKey());
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // Miss — peuplement du cache par la lecture de la liste
    // ----------------------------------------------------------------

    @Test
    void listModules_shouldPopulateCache_onFirstRead() {
        setAuthentication(tenantId, "ROLE_USER");
        assertThat(redisTemplate.hasKey(cacheKey())).isFalse();

        final ResponseEntity<List<ModuleDto>> response = controller.getModules();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cataloguedModule(response).enabled()).isFalse();
        assertThat(redisTemplate.opsForValue().get(cacheKey())).isEqualTo("false");
    }

    // ----------------------------------------------------------------
    // Hit — la liste sert désormais la valeur du cache, pas la BDD
    // ----------------------------------------------------------------

    @Test
    void listModules_shouldServeCachedValue_insteadOfQueryingDatabaseDirectly() {
        // Aucune ligne BDD (= désactivé par défaut) mais Redis dit "true" : si la liste
        // contournait encore le cache (bug corrigé ici), le résultat serait false.
        redisTemplate.opsForValue().set(cacheKey(), "true");
        setAuthentication(tenantId, "ROLE_USER");

        final ResponseEntity<List<ModuleDto>> response = controller.getModules();

        assertThat(cataloguedModule(response).enabled()).isTrue();
    }

    // ----------------------------------------------------------------
    // Invalidation immédiate — pas de statut périmé après un changement
    // ----------------------------------------------------------------

    @Test
    void listModules_shouldReflectActivation_immediately_withoutWaitingForTtl() {
        setAuthentication(tenantId, "ROLE_USER");
        controller.getModules(); // peuple le cache avec "false"
        assertThat(redisTemplate.opsForValue().get(cacheKey())).isEqualTo("false");

        activationService.activate(tenantId, MODULE_ID);

        final ResponseEntity<List<ModuleDto>> response = controller.getModules();
        assertThat(cataloguedModule(response).enabled()).isTrue();
    }

    @Test
    void listModules_shouldReflectDeactivation_immediately_withoutWaitingForTtl() {
        activationService.activate(tenantId, MODULE_ID);
        setAuthentication(tenantId, "ROLE_USER");
        controller.getModules(); // peuple le cache avec "true"
        assertThat(redisTemplate.opsForValue().get(cacheKey())).isEqualTo("true");

        activationService.deactivate(tenantId, MODULE_ID);

        final ResponseEntity<List<ModuleDto>> response = controller.getModules();
        assertThat(cataloguedModule(response).enabled()).isFalse();
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private ModuleDto cataloguedModule(final ResponseEntity<List<ModuleDto>> response) {
        return response.getBody().stream()
                .filter(dto -> dto.id().equals(MODULE_ID))
                .findFirst()
                .orElseThrow();
    }

    private String cacheKey() {
        return "module:status:" + tenantId + ":" + MODULE_ID;
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
}
