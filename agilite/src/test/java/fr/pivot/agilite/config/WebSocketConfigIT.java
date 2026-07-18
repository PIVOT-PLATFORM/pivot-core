package fr.pivot.agilite.config;

import fr.pivot.agilite.poker.ws.PokerChannelInterceptor;
import fr.pivot.agilite.retro.ws.RetroChannelInterceptor;
import fr.pivot.agilite.wheel.ws.WheelChannelInterceptor;
import fr.pivot.agilite.ws.WsSessionRegistry;
import fr.pivot.agilite.ws.WsSessionTrackingHandlerDecoratorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.simp.broker.BrokerAvailabilityEvent;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Integration test proving {@link WebSocketConfig}'s STOMP broker relay actually connects to
 * a real ActiveMQ broker.
 *
 * <p>Only {@link WebSocketConfig} is loaded (no {@code @SpringBootApplication} component scan)
 * — this relay has no dependency on the datasource/Redis/Flyway infrastructure the full
 * application context would otherwise require, so this stays fast and focused. This requires
 * {@link WebSocketConfig#registerStompEndpoints} to register at least one real endpoint (see
 * its class JavaDoc) — a slice loaded with zero endpoints was verified to fail with
 * {@code IllegalStateException: No handlers}.
 *
 * <p>{@link BrokerAvailabilityEvent} is the idiomatic Spring signal for STOMP broker relay
 * connectivity: it is published {@code true} once the relay's "system" TCP connection to the
 * broker succeeds, and {@code false} on disconnect — asserting it is the minimal, correct
 * proof that this configuration reaches a real broker, without needing a full pub/sub
 * round-trip.
 */
@Testcontainers
@SpringBootTest(
        classes = {
            WebSocketConfig.class,
            WebSocketConfigIT.BrokerAvailabilityCaptureConfig.class,
            WebSocketConfigIT.StompBrokerBootstrapConfig.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "pivot.cors.allowed-origins=http://localhost:4200")
class WebSocketConfigIT {

    @Container
    static final GenericContainer<?> activemq =
            new GenericContainer<>(DockerImageName.parse("apache/activemq-classic:6.2.0"))
                    .withExposedPorts(61613, 8161);

    @DynamicPropertySource
    static void activemqProperties(final DynamicPropertyRegistry registry) {
        registry.add("pivot.activemq.relay-host", activemq::getHost);
        registry.add("pivot.activemq.relay-port", () -> activemq.getMappedPort(61613));
    }

    @Autowired
    private AtomicBoolean brokerAvailable;

    /** Maximum time to wait for the relay's "system" connection to report the broker available. */
    private static final long AVAILABILITY_TIMEOUT_MS = 15_000L;

    /** Polling interval while waiting for {@link #brokerAvailable} to flip. */
    private static final long POLL_INTERVAL_MS = 100L;

    /**
     * Given this module's STOMP relay configuration, when the application context starts
     * against a real ActiveMQ broker, then a {@link BrokerAvailabilityEvent} signalling a
     * successful connection is published within a reasonable timeout.
     *
     * @throws InterruptedException if interrupted while polling for the availability flag
     */
    @Test
    void relayConnectsToRealBroker() throws InterruptedException {
        long deadline = System.currentTimeMillis() + AVAILABILITY_TIMEOUT_MS;
        while (!brokerAvailable.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS);
        }
        assertThat(brokerAvailable).isTrue();
    }

    /**
     * Re-enables the STOMP message-broker infrastructure for this narrow test slice.
     *
     * <p><strong>EN53.1 Vague 1 modulith merge — root cause of a 15s hang on this test.</strong>
     * Before the modulith merge, {@link WebSocketConfig} itself carried {@code
     * @EnableWebSocketMessageBroker}, so loading only {@code WebSocketConfig.class} here was
     * enough to bring in Spring's {@code DelegatingWebSocketMessageBrokerConfiguration} (message
     * channels, {@code SimpMessagingTemplate}, and — crucially — the mechanism that actually
     * invokes {@link WebSocketConfig#configureMessageBroker}). The merge correctly removed that
     * annotation from {@link WebSocketConfig} (only one {@code @Enable} is allowed per Spring
     * context; in the aggregated app it now lives on the shell's {@code
     * fr.pivot.notification.config.NotificationWebSocketConfig}) and re-added it, test-scope, on
     * {@link fr.pivot.agilite.AgiliteTestApplication} for the 23 other integration tests that use
     * plain {@code @SpringBootTest} with no explicit {@code classes = ...} (Spring Boot Test
     * auto-discovers {@code AgiliteTestApplication} as the nearest {@code
     * @SpringBootConfiguration}). This test slice, however, explicitly overrides {@code classes =
     * ...} to stay narrow (see this class's own top-level Javadoc) — that bypasses auto-discovery
     * entirely, so {@code AgiliteTestApplication} (and its {@code @EnableWebSocketMessageBroker})
     * was never picked up here. Without it, {@code configureMessageBroker} — the method that
     * calls {@code enableStompBrokerRelay} — was never invoked at all, so the relay never even
     * attempted a connection: {@link #brokerAvailable} stayed permanently {@code false} until
     * {@link #AVAILABILITY_TIMEOUT_MS} elapsed and the test failed. This nested class restores
     * just that one annotation for this slice, without pulling in {@code
     * AgiliteTestApplication}'s datasource/JPA/Flyway/Redis auto-configuration.
     *
     * <p>{@code @TestConfiguration} (not plain {@code @Configuration}), for the same reason as
     * {@link BrokerAvailabilityCaptureConfig} below: this class has no business being picked up
     * by any other test's broader component scan. It carries no {@code @Bean} methods, so even
     * if it were scanned it would be inert — but {@code @TestConfiguration} keeps it excluded via
     * {@code TypeExcludeFilter} regardless, consistent with this module's hardening policy for
     * every nested test-support configuration class.
     */
    @TestConfiguration
    @EnableWebSocketMessageBroker
    static class StompBrokerBootstrapConfig {
    }

    /**
     * Captures {@link BrokerAvailabilityEvent} into a plain flag observable by the test, and
     * supplies mocked EN09.1 collaborators so this slice context (only {@link WebSocketConfig}
     * plus this configuration) can satisfy {@link WebSocketConfig}'s constructor without pulling
     * in the full poker room-isolation stack (Redis, grant store) — irrelevant to what this test
     * proves (the ActiveMQ relay connects).
     *
     * <p><strong>EN53.1 hardening — {@code @TestConfiguration}, not plain {@code
     * @Configuration}.</strong> This nested class lives in package {@code fr.pivot.agilite.config}
     * — inside this module's own {@code @ComponentScan("fr.pivot.agilite")} base package — and its
     * {@code .class} file (compiled to {@code WebSocketConfigIT$BrokerAvailabilityCaptureConfig})
     * sits on the classpath for every other test in this module too, since {@code
     * target/test-classes} is on the classpath alongside {@code target/classes} for the whole
     * module test run. A plain {@code @Configuration} here is a classic Spring Boot test-scan
     * collision risk (documented in the Spring Boot testing reference): any other test in this
     * module using a broad component scan with no protection against it would pick up this
     * class's {@code @Bean} methods too, silently substituting Mockito mocks — whose unstubbed
     * {@code preSend(...)} returns {@code null} unconditionally, for <em>every</em> STOMP frame
     * including CONNECT — for the real {@code @Component}-scanned {@code PokerChannelInterceptor}/
     * {@code RetroChannelInterceptor}/{@code WheelChannelInterceptor} beans everywhere else in the
     * module. {@code @TestConfiguration} is Spring Boot's documented fix: it is recognised and
     * actively excluded by the {@code TypeExcludeFilter} that {@code @SpringBootApplication}
     * (and, after the EN53.1 hardening, {@link fr.pivot.agilite.AgiliteTestApplication}) registers
     * by default — while still fully usable here via this class's own explicit {@code
     * @SpringBootTest(classes = ...)} reference above, unaffected by this annotation swap.
     */
    @TestConfiguration
    static class BrokerAvailabilityCaptureConfig {

        /**
         * Flag flipped to {@code true} the moment the relay reports the broker as available.
         *
         * @return a fresh flag for each test context
         */
        @Bean
        AtomicBoolean brokerAvailable() {
            return new AtomicBoolean(false);
        }

        /**
         * Mocked EN09.1 room-isolation interceptor — unused by this relay-connectivity test.
         *
         * @return a Mockito mock
         */
        @Bean
        PokerChannelInterceptor pokerChannelInterceptor() {
            return mock(PokerChannelInterceptor.class);
        }

        /**
         * Mocked US20.1.2a retro session isolation interceptor — unused by this relay-
         * connectivity test, but required to satisfy {@link WebSocketConfig}'s constructor
         * since this slice loads only {@link WebSocketConfig}, not the full application context.
         *
         * @return a Mockito mock
         */
        @Bean
        RetroChannelInterceptor retroChannelInterceptor() {
            return mock(RetroChannelInterceptor.class);
        }

        /**
         * Mocked US14.3.1 wheel subscription isolation interceptor — unused by this relay-
         * connectivity test, but required to satisfy {@link WebSocketConfig}'s constructor since
         * this slice loads only {@link WebSocketConfig}, not the full application context.
         *
         * @return a Mockito mock
         */
        @Bean
        WheelChannelInterceptor wheelChannelInterceptor() {
            return mock(WheelChannelInterceptor.class);
        }

        /**
         * A <strong>real</strong> (not mocked) session-tracking decorator factory, backed by a
         * real {@link WsSessionRegistry} — Spring's own STOMP endpoint infrastructure invokes
         * {@code decorate(handler)} while building {@code stompWebSocketHandlerMapping} even
         * under {@code WebEnvironment.NONE}; a Mockito mock's unstubbed {@code decorate} returns
         * {@code null} by default, which breaks that infrastructure with "WebSocketHandler is
         * required" (verified empirically). Both collaborators are cheap, dependency-free POJOs,
         * so using the real thing is simpler than stubbing the mock to behave correctly anyway.
         *
         * @return a real decorator factory backed by a fresh registry
         */
        @Bean
        WsSessionTrackingHandlerDecoratorFactory sessionTrackingHandlerDecoratorFactory() {
            return new WsSessionTrackingHandlerDecoratorFactory(new WsSessionRegistry());
        }

        /**
         * Listens for {@link BrokerAvailabilityEvent} and updates the shared flag.
         *
         * @param brokerAvailable the flag to update
         * @return the listener bean
         */
        @Bean
        ApplicationListener<BrokerAvailabilityEvent> brokerAvailabilityListener(final AtomicBoolean brokerAvailable) {
            return event -> brokerAvailable.set(event.isBrokerAvailable());
        }
    }
}
