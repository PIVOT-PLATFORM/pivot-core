package fr.pivot.agilite;

import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * EN53.1 — base class for this module's Spring-context integration tests, wiring them onto the
 * module-wide Testcontainers singletons in {@link AgiliteTestContainers} instead of each IT class
 * starting (and eventually exhausting the CI runner with) its own private Postgres + Redis pair.
 *
 * <p>Mirrors the shell's own {@code fr.pivot.AbstractIntegrationTest} pattern: the containers are
 * started in {@link AgiliteTestContainers}'s {@code static} initializer — guaranteed to run
 * before Spring's {@code SpringExtension} (transitively pulled in by {@link SpringBootTest})
 * creates the {@code ApplicationContext} that needs their coordinates — and this class's {@link
 * #overrideProperties} exposes those coordinates via {@code @DynamicPropertySource}, exactly as
 * every one of this module's IT classes previously did individually.
 *
 * <p><strong>What subclasses keep doing themselves:</strong> each concrete IT class still
 * declares its own {@code @SpringBootTest(webEnvironment = ...)} (the value genuinely differs —
 * {@code RANDOM_PORT} for controller/websocket ITs, {@code NONE} for service-level ones) — a
 * class-level {@code @SpringBootTest} on a subclass overrides the one inherited from here rather
 * than merging with it, so the {@code webEnvironment}/{@code classes} attributes a subclass needs
 * must stay on that subclass. {@code @ActiveProfiles("test")} does not need to be repeated —
 * unlike {@code @SpringBootTest}, Spring's {@code TestContext} framework walks the full class
 * hierarchy for it, so declaring it once here is enough.
 *
 * <p>Every subclass that seeds real tenants/users/teams via {@link PlatformAuthTestSupport}
 * (almost all of them) used to call {@link PlatformAuthTestSupport#createPublicSchema} itself,
 * once per container, from its own {@code @DynamicPropertySource} method. That call is idempotent
 * ({@code CREATE TABLE IF NOT EXISTS}) and is now made exactly once here instead, against the
 * single shared {@link AgiliteTestContainers#POSTGRES} — safe to still be invoked once per
 * distinct Spring {@code ApplicationContext} Spring's test context cache builds across the
 * differently-configured subclasses in this module.
 *
 * <p><strong>Not extended by every {@code *IT} class in this module:</strong> a handful of
 * Redis-only ITs (e.g. {@code RoomAccessGrantServiceIT}) deliberately never boot a Spring context
 * at all — instantiating their single collaborator directly against a real {@code
 * StringRedisTemplate} — and reference {@link AgiliteTestContainers#REDIS} directly instead, to
 * keep that fast, context-free nature. {@code WebSocketConfigIT} (the only ActiveMQ-relay IT in
 * this module) never used the Postgres/Redis containers in the first place — its slice context
 * loads only {@code WebSocketConfig}, with no datasource/Redis auto-configuration to satisfy — so
 * it is left untouched as well.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractAgiliteIntegrationTest {

    /** The module-wide singleton Postgres container — see {@link AgiliteTestContainers}. */
    protected static final PostgreSQLContainer<?> postgres = AgiliteTestContainers.POSTGRES;

    /** The module-wide singleton Redis container — see {@link AgiliteTestContainers}. */
    protected static final GenericContainer<?> redis = AgiliteTestContainers.REDIS;

    /**
     * Wires the shared containers' coordinates into the Spring {@code Environment}, and seeds the
     * {@code public} schema (owned by {@code pivot-core}, not by this module's own Flyway, which
     * manages {@code agilite} only) before the Spring context — and therefore Flyway, whose {@code
     * agilite.*} migrations carry FK references into {@code public.tenants}/{@code public.users}/
     * {@code public.teams} — starts.
     *
     * @param registry the dynamic property registry
     * @throws Exception if seeding the {@code public} schema fails
     */
    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Spring Boot 4.x FlywayConnectionDetails are derived from spring.flyway.* independently
        // of the main datasource — set explicitly to guarantee Flyway targets the same container
        // (mirrors fr.pivot.AbstractIntegrationTest in the shell).
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        PlatformAuthTestSupport.createPublicSchema(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
