package fr.pivot.collaboratif;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;

/**
 * EN53.2 Vague 2 — test-scope replacement for the removed {@code PivotCollaboratifApplication}
 * boot class (deleted from {@code src/main/java}: the modulith has a single boot class, {@code
 * fr.pivot.PivotBackendApplication}, living in the {@code app} module). Mirrors the agilite
 * module's own {@code AgiliteTestApplication} (EN53.1 Vague 1) — same rationale, same shape.
 *
 * <p><strong>Why this class exists at all:</strong> this module's own integration tests (e.g.
 * {@code BoardControllerIT}, {@code WhiteboardCanvasIT}, {@code AuthenticationIT}) use plain
 * {@code @SpringBootTest} with no explicit {@code classes = ...}, which makes Spring Boot Test
 * search upward from the test's own package for a class annotated with {@code
 * @SpringBootConfiguration} (transitively provided by {@code @SpringBootApplication}). With no
 * such class anywhere on this module's test classpath, every one of those tests fails at
 * context-startup with {@code IllegalStateException: Unable to find a @SpringBootConfiguration}.
 * Placing this class at the exact same package ({@code fr.pivot.collaboratif}) that {@code
 * PivotCollaboratifApplication} used to occupy, but under {@code src/test/java}, keeps those test
 * classes byte-for-byte unmodified while satisfying that lookup — and, being test-scoped, this
 * class is never compiled into the production library JAR that {@code pivot-core-app} depends
 * on, so it cannot collide with the app's own {@code fr.pivot.PivotBackendApplication} boot
 * class or double-register any bean once aggregated.
 *
 * <p>Deliberately {@code @EnableAutoConfiguration} + {@code @ComponentScan} spelled out
 * explicitly (rather than {@code @SpringBootApplication}) purely so the class-level Javadoc can
 * document each piece; behaviourally equivalent to the original {@code
 * @SpringBootApplication(scanBasePackages = "fr.pivot.collaboratif")} — <strong>including its
 * default {@code excludeFilters}</strong> ({@link TypeExcludeFilter}, {@link
 * AutoConfigurationExcludeFilter}), reproduced explicitly below. Omitting these two is not
 * cosmetic: without {@link TypeExcludeFilter}, this module's own {@code @ComponentScan(
 * "fr.pivot.collaboratif")} is not just broader than {@code @SpringBootApplication}'s — it
 * actively sweeps up test-only nested configuration classes that are supposed to stay opt-in
 * (e.g. {@code CollaboratifWebSocketConfigRelayIT}'s nested {@code @TestConfiguration} classes) — the exact
 * failure mode diagnosed and fixed for the agilite module's own {@code WebSocketConfigIT}
 * (leaked Mockito mock silently vetoing every inbound STOMP frame, EN53.1 Vague 1 regression
 * fix). {@link TypeExcludeFilter} is precisely the hook {@code @TestConfiguration} relies on to
 * stay excluded from an unrelated scan like this one.
 *
 * <p>{@link EntityScan}/{@link EnableJpaRepositories} extend scanning to {@code
 * fr.pivot.core.team} (same rationale as the original boot class and the agilite module: {@code
 * fr.pivot.core.team.Team}/{@code TeamMember} are exported as-is by {@code pivot-core-starter},
 * and Spring Data JPA's entity/repository scanning defaults to the narrow {@code
 * scanBasePackages} above, which would otherwise miss them — this module's {@code
 * PlatformAuthTestSupport} seeds {@code public.teams}/{@code public.team_members} directly via
 * raw JDBC, but no production code in this module currently reads {@code Team}/{@code
 * TeamMember} through Spring Data; the scan is carried over for parity with agilite and to keep
 * the door open). {@link EnableScheduling} is carried over for parity with the agilite module's
 * own test application even though this module currently has no {@code @Scheduled} method of its
 * own — harmless no-op if unused, avoids a divergent test bootstrap shape between modules.
 *
 * <p>{@code @EnableWebSocketMessageBroker} ported HERE (test-scope): removed from {@code
 * fr.pivot.collaboratif.config.CollaboratifWebSocketConfig} (only one {@code @Enable} is allowed per Spring
 * context; in the aggregated app it is the shell's own {@code NotificationWebSocketConfig} that
 * carries it, exactly like the agilite module). This module's isolated test context has no
 * shell config on its classpath, so without this annotation here no STOMP infra bean (in
 * particular {@code SimpMessagingTemplate}) would exist and every WebSocket-dependent service
 * (e.g. {@code WhiteboardBroadcastService}, {@code CanvasActionService}) could not be injected.
 * {@code CollaboratifWebSocketConfig} remains component-scanned as a plain {@code WebSocketMessageBrokerConfigurer}
 * (broker registration — gated behind its own {@code pivot.collaboratif.websocket.broker.self-managed}
 * flag, see that class's JavaDoc —, endpoints, interceptors). Never packaged into the production
 * JAR once aggregated, so no double {@code @Enable} risk.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = "fr.pivot.collaboratif",
        excludeFilters = {
            @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
        })
@ConfigurationPropertiesScan("fr.pivot.collaboratif")
@EntityScan(basePackages = {"fr.pivot.collaboratif", "fr.pivot.core.team"})
@EnableJpaRepositories(basePackages = {"fr.pivot.collaboratif", "fr.pivot.core.team"})
@EnableScheduling
@EnableWebSocketMessageBroker
public class CollaboratifTestApplication {
}
