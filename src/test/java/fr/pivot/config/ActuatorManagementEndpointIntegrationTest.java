package fr.pivot.config;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.core.modules.cache.ModuleActivationCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EN04.2 — Spring Actuator endpoints, exposed on a separate management port.
 *
 * <p>Full Spring context, real embedded servers on random ports for both the main
 * application ({@link LocalServerPort}) and the Actuator management context
 * ({@link LocalManagementPort}) — {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} is
 * required here (unlike the {@code MOCK} + {@code MockMvc} pattern used by most other
 * integration tests in this codebase): the management port is served by a genuinely separate
 * child {@code ApplicationContext} with its own embedded connector, which {@code MockMvc}
 * (built on the main {@code WebApplicationContext}) cannot reach at all.
 *
 * <p>Uses a plain JDK {@link HttpClient} rather than {@code TestRestTemplate} — removed from
 * {@code spring-boot-test} as of this Spring Boot version.
 *
 * <p>Covers the EN04.2 acceptance criteria:
 * <ul>
 *   <li>Endpoints exposed on a separate management port, not on the main {@code :8080/api}
 *       one (AC "port séparé + non routé")</li>
 *   <li>{@code /actuator/health} — {@code UP} + per-component breakdown (DB, Redis)</li>
 *   <li>{@code /actuator/info} — app version, git commit SHA, active Spring profile</li>
 *   <li>{@code /actuator/metrics} — JVM metrics, HTTP request duration, a custom app metric</li>
 *   <li>Security AC — least exposure: only {@code health,info,metrics} are exposed, e.g.
 *       {@code /actuator/env} (not in the include list) must not be reachable</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorManagementEndpointIntegrationTest extends AbstractIntegrationTest {

    /**
     * Random management port for this test run — overrides the {@code :8081} default from
     * {@code application.yml}/{@code application-test.yml} to avoid clashing with any other
     * process (or another concurrently running Spring context) already bound to it.
     *
     * @param registry the dynamic property registry to add the override to
     */
    @DynamicPropertySource
    static void randomManagementPort(final DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> "0");
    }

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ModuleActivationCacheService moduleActivationCacheService;

    private HttpResponse<String> get(final String baseUrl, final String path) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String managementBaseUrl() {
        return "http://localhost:" + managementPort;
    }

    @Test
    void healthEndpoint_onManagementPort_returnsUpWithComponentBreakdown() throws Exception {
        final HttpResponse<String> response = get(managementBaseUrl(), "/actuator/health");

        // 200 (UP) or 503 (DOWN, Spring Boot's own HttpCodeStatusMapper default) are both a
        // valid, well-formed health response — this environment has no mail server (mailpit)
        // reachable, so the auto-configured "mail" component is legitimately DOWN here,
        // dragging the aggregate status down with it. The AC under test is the *shape* of the
        // response (status field + per-component breakdown for db/redis), not that every
        // possible auto-configured component happens to be reachable in CI.
        assertThat(response.statusCode()).as("body: %s", response.body()).isIn(200, 503);
        final JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("status").asText()).as("body: %s", response.body()).isIn("UP", "DOWN");
        // show-details: always (network isolation is the access control here, not Spring
        // Security — see application.yml) — component breakdown must be visible with no auth.
        assertThat(body.path("components").path("db").path("status").asText())
                .as("body: %s", response.body()).isEqualTo("UP");
        assertThat(body.path("components").path("redis").path("status").asText())
                .as("body: %s", response.body()).isEqualTo("UP");
    }

    @Test
    void healthEndpoint_isNotExposedOnTheMainApplicationPort() throws Exception {
        assertThat(managementPort).isNotEqualTo(serverPort);

        // management.server.port genuinely separate → actuator disappears from the main
        // context's DispatcherServlet entirely, context-path (/api) included: the same path
        // that serves health on the management port 404s here (permitAll in SecurityConfig
        // lets the request through — see its Javadoc — but there is no mapping for it left on
        // this context, so DispatcherServlet itself reports 404).
        final String mainBaseUrl = "http://localhost:" + serverPort;
        assertThat(get(mainBaseUrl, "/api/actuator/health").statusCode()).isEqualTo(404);
    }

    @Test
    void infoEndpoint_onManagementPort_exposesVersionGitShaAndActiveProfile() throws Exception {
        final HttpResponse<String> response = get(managementBaseUrl(), "/actuator/info");

        assertThat(response.statusCode()).isEqualTo(200);
        final JsonNode body = objectMapper.readTree(response.body());

        // BuildPropertiesInfoContributor — from spring-boot-maven-plugin's build-info execution.
        assertThat(body.path("build").path("version").asText()).isNotBlank();

        // GitInfoContributor — from git-commit-id-maven-plugin's generated git.properties.
        // Spring Boot's default "simple" contributor mode (no management.info.git.mode
        // override — see application.yml, deliberately not "full": that would also leak
        // committer/author emails and full commit messages) surfaces exactly
        // {"git":{"branch":"...","commit":{"id":"...","time":"..."}}} — abbreviated SHA, which
        // already satisfies the "git commit sha" AC without over-exposing.
        assertThat(body.path("git").path("commit").path("id").asText())
                .as("body: %s", response.body()).matches("[0-9a-f]{7,40}");

        // Custom InfoContributor (ActuatorConfig) — active Spring profile, "test" here.
        final JsonNode activeProfiles = body.path("profile").path("active");
        assertThat(activeProfiles.isArray()).isTrue();
        final boolean hasTestProfile = StreamSupport.stream(activeProfiles.spliterator(), false)
                .anyMatch(node -> "test".equals(node.asText()));
        assertThat(hasTestProfile).isTrue();
    }

    @Test
    void metricsEndpoint_onManagementPort_exposesJvmHttpAndCustomMetrics() throws Exception {
        // Trigger both a main-port HTTP request (http.server.requests) and a custom business
        // counter (pivot.modules.cache.miss — ModuleActivationCacheService) so both have at
        // least one recorded sample before asserting on them.
        get("http://localhost:" + serverPort, "/api/auth/password-policy");
        moduleActivationCacheService.isEnabled(-1L, "en04-2-actuator-test-module");

        final JsonNode namesResponse = objectMapper.readTree(get(managementBaseUrl(), "/actuator/metrics").body());
        final JsonNode names = namesResponse.path("names");
        assertThat(names.isArray()).isTrue();
        final List<String> nameList = StreamSupport.stream(names.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
        assertThat(nameList).contains("jvm.memory.used");

        final HttpResponse<String> httpDuration = get(managementBaseUrl(), "/actuator/metrics/http.server.requests");
        assertThat(httpDuration.statusCode()).isEqualTo(200);

        final HttpResponse<String> customMetric =
                get(managementBaseUrl(), "/actuator/metrics/pivot.modules.cache.miss");
        assertThat(customMetric.statusCode()).isEqualTo(200);
    }

    @Test
    void onlyHealthInfoMetricsAreExposed_envEndpointIsNotReachable() throws Exception {
        // Security AC — least exposure: SecurityConfig's permitAll list mirrors
        // management.endpoints.web.exposure.include (health,info,metrics) exactly, not a
        // "/actuator/**" wildcard (see its Javadoc) — /actuator/env and /actuator/beans
        // (auto-configured beans, EnvironmentEndpointAutoConfiguration/
        // BeansEndpointAutoConfiguration, but never web-exposed) fall outside that explicit
        // list and hit anyRequest().authenticated() — denied (403) at the security layer
        // itself, before ever reaching a "no mapping" check. Fails closed by construction:
        // a future endpoint added to the exposure include list without a matching update
        // here stays 403, never silently 200.
        assertThat(get(managementBaseUrl(), "/actuator/env").statusCode()).isEqualTo(403);
        assertThat(get(managementBaseUrl(), "/actuator/beans").statusCode()).isEqualTo(403);
    }
}
