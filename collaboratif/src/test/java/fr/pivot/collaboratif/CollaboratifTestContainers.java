package fr.pivot.collaboratif;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * EN53.2 — module-wide Testcontainers singletons for the {@code collaboratif} module's
 * integration test suite. Mirrors the agilite module's own {@code AgiliteTestContainers} (EN53.1
 * Vague 1) — same rationale, same shape.
 *
 * <p>Before this class existed, each of this module's ~35 {@code *IT} classes declared its own
 * {@code @Container static PostgreSQLContainer}/{@code GenericContainer(redis)} pair, started
 * independently by the JUnit 5 {@code @Testcontainers} extension for that class alone. On a full
 * module run that spun up roughly one Postgres + one Redis container per IT class — the same
 * CI-runner-exhaustion failure mode already diagnosed and fixed for the agilite module (Postgres
 * crashing mid-run, every subsequent IT class cascading into unrelated failures).
 *
 * <p>This class holds exactly one {@link PostgreSQLContainer} and one Redis {@link
 * GenericContainer} for the <strong>entire</strong> {@code collaboratif} module test run —
 * mirroring both the shell's own {@code fr.pivot.AbstractIntegrationTest} and the agilite
 * module's {@code AgiliteTestContainers} singleton-container pattern. Both are started in a
 * {@code static} initializer block (not via {@code @Container}/{@code @Testcontainers}), which
 * guarantees they are already running before any JUnit extension — including {@code
 * SpringExtension} — creates an {@code ApplicationContext} that depends on their coordinates.
 *
 * <p>Being plain {@code static final} fields on a plain class (not a JUnit {@code @Testcontainers}
 * class, no lifecycle annotations at all), the two containers are created exactly once per JVM —
 * the very first test class that touches either {@link #POSTGRES} or {@link #REDIS} (directly, or
 * indirectly through {@link AbstractCollaboratifIntegrationTest}) triggers this class's static
 * initializer, and every other test class in the same JVM fork reuses the same running
 * containers. This assumes the module's Failsafe/Surefire execution keeps the JVM fork alive for
 * the whole IT run (the default {@code reuseForks=true}, single fork) — if the build is ever
 * reconfigured to fork a fresh JVM per test class, this singleton degrades back to one container
 * pair per class and must be revisited.
 *
 * <p>{@code CollaboratifWebSocketConfigRelayIT} (the one ActiveMQ-relay IT in this module) extends {@link
 * AbstractCollaboratifIntegrationTest} for its Postgres/Redis needs — it additionally declares
 * its own per-class {@code @Container GenericContainer} for ActiveMQ, since no other test in this
 * module needs a broker and a module-wide singleton for it would be wasted overhead everywhere
 * else.
 */
public final class CollaboratifTestContainers {

    /** The single PostgreSQL 18 container shared by every {@code collaboratif} IT that needs a real database. */
    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    /** The single Redis container shared by every {@code collaboratif} IT that needs a real Redis instance. */
    @SuppressWarnings("resource")
    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        // Static initializer guarantees both containers are running before any JUnit extension
        // (including SpringExtension.beforeAll, for classes going through
        // AbstractCollaboratifIntegrationTest) creates an ApplicationContext or opens a Redis
        // connection — see AbstractIntegrationTest's Javadoc (shell) / AgiliteTestContainers'
        // own Javadoc for the exact race this avoids.
        POSTGRES.start();
        REDIS.start();
    }

    private CollaboratifTestContainers() {
    }
}
