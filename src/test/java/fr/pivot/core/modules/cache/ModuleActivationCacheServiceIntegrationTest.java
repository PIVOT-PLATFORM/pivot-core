package fr.pivot.core.modules.cache;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.core.modules.ModuleActivation;
import fr.pivot.core.modules.ModuleActivationRepository;
import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.PivotModule;
import fr.pivot.core.tenant.TenantContext;
import fr.pivot.tenant.repository.TenantRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tests d'intégration {@link ModuleActivationCacheService} — Redis réel via Testcontainers
 * (image {@code redis:7-alpine}) + PostgreSQL réel (hérité de {@link AbstractIntegrationTest}).
 *
 * <p>Traçabilité EN03.3 :
 * <ul>
 *   <li>clé {@code module:status:{tenantId}:{moduleId}} — hit prioritaire sur la BDD ;</li>
 *   <li>miss → peuplement du cache avec TTL configuré ({@code modules.cache.ttl-seconds}) ;</li>
 *   <li>expiration TTL → nouvelle lecture BDD ;</li>
 *   <li>invalidation immédiate sur événement d'activation/désactivation ;</li>
 *   <li>métriques Micrometer hit/miss dans un contexte Spring réel.</li>
 * </ul>
 *
 * <p>Le scénario « Redis indisponible » est couvert séparément par
 * {@link ModuleActivationCacheServiceRedisDownIntegrationTest} (contexte Spring dédié,
 * port Redis fermé) pour ne pas interférer avec les tests ci-dessous qui exigent un
 * Redis fonctionnel.
 */
@Import(ModuleActivationCacheServiceIntegrationTest.TestModuleConfig.class)
class ModuleActivationCacheServiceIntegrationTest extends AbstractIntegrationTest {

    private static final String MODULE_ID = "it-cache-module";
    private static final long TTL_SECONDS = 2L;

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        // Cf. AbstractIntegrationTest : démarrage statique pour garantir la disponibilité
        // du conteneur avant la création du contexte Spring.
        REDIS.start();
    }

    @DynamicPropertySource
    static void configureRedis(final DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("modules.cache.ttl-seconds", () -> TTL_SECONDS);
    }

    @Autowired
    private ModuleActivationCacheService cacheService;

    @Autowired
    private ModuleActivationService activationService;

    @Autowired
    private ModuleActivationRepository repository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meterRegistry;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.findBySlug("pivot-saas").orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll();
        redis.delete(cacheKey());
    }

    // ----------------------------------------------------------------
    // Cache hit — priorité sur la BDD
    // ----------------------------------------------------------------

    @Test
    void isEnabled_returnsCachedValue_ignoringBdd_onCacheHit() {
        // Aucune ligne BDD (= désactivé par défaut) mais le cache dit "activé" :
        // si isEnabled() interrogeait la BDD, le résultat serait false.
        redis.opsForValue().set(cacheKey(), "true", TTL_SECONDS, TimeUnit.SECONDS);

        assertThat(cacheService.isEnabled(tenantId, MODULE_ID)).isTrue();
    }

    // ----------------------------------------------------------------
    // Cache miss — peuplement avec TTL
    // ----------------------------------------------------------------

    @Test
    void isEnabled_populatesCache_withConfiguredTtl_onMiss() {
        persistDirectly(true);
        assertThat(redis.hasKey(cacheKey())).isFalse();

        final boolean result = cacheService.isEnabled(tenantId, MODULE_ID);

        assertThat(result).isTrue();
        assertThat(redis.opsForValue().get(cacheKey())).isEqualTo("true");
        final Long expire = redis.getExpire(cacheKey(), TimeUnit.SECONDS);
        assertThat(expire).isPositive().isLessThanOrEqualTo(TTL_SECONDS);
    }

    // ----------------------------------------------------------------
    // Expiration TTL — nouvelle lecture BDD
    // ----------------------------------------------------------------

    @Test
    void isEnabled_reFetchesFromBdd_afterCacheExpires() {
        final ModuleActivation row = persistDirectly(true);
        assertThat(cacheService.isEnabled(tenantId, MODULE_ID)).isTrue();

        // Changement BDD hors bus d'événements (simulateur d'édition directe) : le cache
        // ne doit refléter ce changement qu'après expiration du TTL.
        row.setEnabled(false);
        repository.saveAndFlush(row);

        await().atMost(Duration.ofSeconds(TTL_SECONDS + 3)).pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertThat(redis.hasKey(cacheKey())).isFalse());

        assertThat(cacheService.isEnabled(tenantId, MODULE_ID)).isFalse();
    }

    // ----------------------------------------------------------------
    // Invalidation immédiate sur événement — pas d'attente du TTL
    // ----------------------------------------------------------------

    @Test
    void activate_writesThroughCache_immediately() {
        activationService.activate(tenantId, MODULE_ID);

        assertThat(redis.opsForValue().get(cacheKey())).isEqualTo("true");
    }

    @Test
    void deactivate_writesThroughCache_immediately() {
        activationService.activate(tenantId, MODULE_ID);
        activationService.deactivate(tenantId, MODULE_ID);

        assertThat(redis.opsForValue().get(cacheKey())).isEqualTo("false");
    }

    // ----------------------------------------------------------------
    // Métriques Micrometer — hit/miss dans un contexte Spring réel
    // ----------------------------------------------------------------

    @Test
    void isEnabled_incrementsHitAndMissCounters() {
        persistDirectly(true);
        final double missBefore = counterValue("pivot.modules.cache.miss");
        final double hitBefore = counterValue("pivot.modules.cache.hit");

        cacheService.isEnabled(tenantId, MODULE_ID); // miss (peuple le cache)
        cacheService.isEnabled(tenantId, MODULE_ID); // hit (lit le cache)

        assertThat(counterValue("pivot.modules.cache.miss")).isEqualTo(missBefore + 1);
        assertThat(counterValue("pivot.modules.cache.hit")).isEqualTo(hitBefore + 1);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private ModuleActivation persistDirectly(final boolean enabled) {
        final ModuleActivation row = new ModuleActivation(tenantId, MODULE_ID);
        row.setEnabled(enabled);
        return repository.saveAndFlush(row);
    }

    private String cacheKey() {
        return "module:status:" + tenantId + ":" + MODULE_ID;
    }

    private double counterValue(final String name) {
        final Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * Simule un repo module externe déclarant son {@link PivotModule} — nécessaire à la
     * validation {@link ModuleActivationService} (module inconnu = rejet).
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestModuleConfig {

        @Bean
        PivotModule cacheTestModule() {
            return new PivotModule() {
                @Override
                public String getId() {
                    return MODULE_ID;
                }

                @Override
                public String getName() {
                    return "Module de test TI — cache";
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
