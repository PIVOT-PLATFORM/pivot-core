package fr.pivot.core.modules.cache;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.core.modules.ModuleActivationRepository;
import fr.pivot.core.modules.ModuleActivationService;
import fr.pivot.core.modules.PivotModule;
import fr.pivot.core.tenant.TenantContext;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TI dédié : Redis totalement injoignable (port fermé, aucune écoute) — vérifie que
 * {@link ModuleActivationCacheService} bascule sur {@link ModuleActivationService} (source
 * de vérité BDD) sans jamais propager d'exception vers l'appelant (pas de 500 API).
 *
 * <p>Classe séparée de {@link ModuleActivationCacheServiceIntegrationTest} : nécessite un
 * contexte Spring dédié (propriétés {@code spring.data.redis.host}/{@code port} pointant vers
 * un port fermé) qui ne doit pas être partagé avec les tests exigeant un Redis fonctionnel.
 *
 * <p>Le port est obtenu en ouvrant puis refermant immédiatement un {@link ServerSocket} sur
 * le port {@code 0} (attribution système d'un port libre) : la connexion Redis échoue alors
 * par "connection refused", sans dépendre d'un port fixe potentiellement occupé en CI.
 */
@Import(ModuleActivationCacheServiceRedisDownIntegrationTest.TestModuleConfig.class)
class ModuleActivationCacheServiceRedisDownIntegrationTest extends AbstractIntegrationTest {

    private static final String MODULE_ID = "it-cache-redis-down-module";

    @DynamicPropertySource
    static void configureUnreachableRedis(final DynamicPropertyRegistry registry) throws IOException {
        final int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> closedPort);
    }

    @Autowired
    private ModuleActivationCacheService cacheService;

    @Autowired
    private ModuleActivationService activationService;

    @Autowired
    private ModuleActivationRepository repository;

    @Autowired
    private TenantRepository tenantRepository;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenantRepository.findBySlug("pivot-saas").orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll();
    }

    @Test
    void isEnabled_fallsBackToBdd_withoutThrowing_whenModuleEnabled() {
        activationService.activate(tenantId, MODULE_ID);

        assertThatCode(() -> assertThat(cacheService.isEnabled(tenantId, MODULE_ID)).isTrue())
                .doesNotThrowAnyException();
    }

    @Test
    void isEnabled_fallsBackToBdd_withoutThrowing_whenModuleDisabled() {
        assertThatCode(() -> assertThat(cacheService.isEnabled(tenantId, MODULE_ID)).isFalse())
                .doesNotThrowAnyException();
    }

    /**
     * Simule un repo module externe déclarant son {@link PivotModule}.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestModuleConfig {

        @Bean
        PivotModule redisDownTestModule() {
            return new PivotModule() {
                @Override
                public String getId() {
                    return MODULE_ID;
                }

                @Override
                public String getName() {
                    return "Module de test TI — Redis indisponible";
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
