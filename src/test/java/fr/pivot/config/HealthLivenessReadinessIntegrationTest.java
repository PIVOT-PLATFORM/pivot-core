package fr.pivot.config;

import fr.pivot.AbstractIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EN04.4 — liveness/readiness health group separation, exercised against a real embedded
 * management context (same pattern as {@link ActuatorManagementEndpointIntegrationTest},
 * EN04.2).
 *
 * <p>Uses its own dedicated Testcontainers Redis — not the shared CI/local Redis instance
 * {@link AbstractIntegrationTest} relies on for other test classes (see its Javadoc) — so the
 * last test in this class can stop it to simulate this Enabler's "module KO" AC scenario
 * without disturbing any other test class or the shared CI Redis service. {@link Order}
 * enforces that this destructive test runs last: the two tests before it need Redis alive.
 *
 * <p>{@link DirtiesContext} (class mode {@code AFTER_CLASS}) forces Spring's test context
 * cache to close this specific {@code ApplicationContext} once this class finishes rather
 * than keeping it cached for potential reuse — after the last test kills Redis, this
 * context's Lettuce client is left with a dead target and its background
 * {@code ConnectionWatchdog} would otherwise keep retrying to reconnect indefinitely (Lettuce
 * default {@code autoReconnect}), burning CPU for the remainder of the test run and, observed
 * in CI, degrading unrelated later test classes sharing the same JVM/Surefire fork.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HealthLivenessReadinessIntegrationTest extends AbstractIntegrationTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        // Static initializer, same reasoning as AbstractIntegrationTest's own POSTGRES: must be
        // running before SpringExtension builds the ApplicationContext.
        REDIS.start();
    }

    @DynamicPropertySource
    static void configureRedisAndManagementPort(final DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Random management port — same reasoning as ActuatorManagementEndpointIntegrationTest
        // (EN04.2): avoids clashing with the :8081 default if anything else in the same CI run
        // is already bound to it.
        registry.add("management.server.port", () -> "0");
    }

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpResponse<String> get(final String path) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + managementPort + path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void liveness_isJvmOnly_neverIncludesDependencyComponents() throws Exception {
        final HttpResponse<String> response = get("/actuator/health/liveness");

        assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(200);
        final JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("status").asText()).isEqualTo("UP");
        // JVM-only group (management.endpoint.health.group.liveness.include=livenessState,
        // application.yml) — db/redis/flyway must never appear here, only in readiness.
        assertThat(body.path("components").has("redis")).isFalse();
        assertThat(body.path("components").has("db")).isFalse();
        assertThat(body.path("components").has("flyway")).isFalse();
    }

    @Test
    @Order(2)
    void readiness_withDependenciesUp_includesDbRedisFlywayAllUp() throws Exception {
        final HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(200);
        final JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.path("components").path("db").path("status").asText()).isEqualTo("UP");
        assertThat(body.path("components").path("redis").path("status").asText()).isEqualTo("UP");
        assertThat(body.path("components").path("flyway").path("status").asText()).isEqualTo("UP");
    }

    @Test
    @Order(3)
    void readiness_goesDown_whenRedisIsKilled_butLivenessStaysUp() throws Exception {
        // "Simulation module KO" (this Enabler's AC): a readiness dependency (Redis)
        // disappears. Readiness must reflect that (503/DOWN) — that is what lets nginx's
        // passive health check (pivot-ui nginx.conf, EN04.4) eventually take this instance out
        // of its upstream pool — while liveness stays UP: the JVM itself is fine, restarting
        // the container would not fix a dead Redis and would only cause needless churn. Last
        // test in the class (@Order(3)) precisely because it kills the container the two
        // tests above depend on.
        REDIS.stop();

        final HttpResponse<String> readiness = get("/actuator/health/readiness");
        assertThat(readiness.statusCode()).as("body: %s", readiness.body()).isEqualTo(503);
        final JsonNode readinessBody = objectMapper.readTree(readiness.body());
        assertThat(readinessBody.path("status").asText()).isEqualTo("DOWN");
        assertThat(readinessBody.path("components").path("redis").path("status").asText())
                .isEqualTo("DOWN");

        final HttpResponse<String> liveness = get("/actuator/health/liveness");
        assertThat(liveness.statusCode()).as("body: %s", liveness.body()).isEqualTo(200);
        assertThat(objectMapper.readTree(liveness.body()).path("status").asText()).isEqualTo("UP");
    }
}
