package fr.pivot;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests.
 *
 * <p>Starts a PostgreSQL 18 container via Testcontainers (static initializer guarantees startup
 * before SpringExtension creates the ApplicationContext). Flyway runs {@code db/migration}
 * + {@code db/seeds} (profile {@code test}).
 *
 * <p>Redis is supplied by the CI service or a local instance. Tests must not invoke
 * Redis-backed services (rate limiting) unless a Redis container is also wired up.
 * The {@link TestCacheConfig} provides an in-memory {@link ConcurrentMapCacheManager}
 * so {@code @EnableCaching} resolves without relying on Redis cache auto-configuration.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AbstractIntegrationTest.TestCacheConfig.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    static {
        // Static initializer guarantees the container is running before any JUnit extension
        // (including SpringExtension.beforeAll) creates the Spring ApplicationContext.
        // This avoids the race condition with @Testcontainers + @ServiceConnection where
        // SpringExtension may create the context before TestcontainersExtension starts the container.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Spring Boot 4.x FlywayConnectionDetails are derived from spring.flyway.* independently
        // of the main datasource — set explicitly to guarantee Flyway targets the same container.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class TestCacheConfig {
        @Bean
        public CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("feature-flags");
        }
    }
}
