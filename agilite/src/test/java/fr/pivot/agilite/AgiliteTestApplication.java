package fr.pivot.agilite;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;

/**
 * EN53.1 Vague 1 — test-scope replacement for the removed {@code PivotAgiliteApplication} boot
 * class (deleted from {@code src/main/java}: the modulith has a single boot class, {@code
 * fr.pivot.PivotBackendApplication}, living in the {@code app} module).
 *
 * <p><strong>Why this class exists at all:</strong> 23 of this module's own integration tests
 * (e.g. {@code RetroPhaseSchedulerIT}, {@code PokerRoomControllerIT}, {@code WheelControllerIT})
 * use plain {@code @SpringBootTest} with no explicit {@code classes = ...}, which makes Spring
 * Boot Test search upward from the test's own package for a class annotated with {@code
 * @SpringBootConfiguration} (transitively provided by {@code @SpringBootApplication}). With no
 * such class anywhere on this module's test classpath, every one of those tests fails at
 * context-startup with {@code IllegalStateException: Unable to find a @SpringBootConfiguration}.
 * Placing this class at the exact same package ({@code fr.pivot.agilite}) that {@code
 * PivotAgiliteApplication} used to occupy, but under {@code src/test/java}, keeps those 23 test
 * classes byte-for-byte unmodified while satisfying that lookup — and, being test-scoped, this
 * class is never compiled into the production library JAR that {@code pivot-core-app} depends
 * on, so it cannot collide with the app's own {@code fr.pivot.PivotBackendApplication} boot
 * class or double-register any bean once aggregated.
 *
 * <p>Deliberately {@code @EnableAutoConfiguration} + {@code @ComponentScan} spelled out
 * explicitly (rather than {@code @SpringBootApplication}) purely so the class-level Javadoc can
 * document each piece; behaviourally equivalent to the original {@code
 * @SpringBootApplication(scanBasePackages = "fr.pivot.agilite")}.
 *
 * <p>{@link EntityScan}/{@link EnableJpaRepositories} extend scanning to {@code
 * fr.pivot.core.team} (the same rationale as the original boot class: {@code
 * fr.pivot.core.team.Team}/{@code TeamMember}/{@code TeamRepository}/{@code TeamMemberRepository}
 * are exported as-is by {@code pivot-core-starter}, and Spring Data JPA's entity/repository
 * scanning defaults to the narrow {@code scanBasePackages} above, which would otherwise miss
 * them). {@link EnableScheduling} is required for {@code RetroPhaseScheduler}'s {@code
 * @Scheduled} timer-expiry check to run in {@code RetroPhaseSchedulerIT}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan("fr.pivot.agilite")
@ConfigurationPropertiesScan("fr.pivot.agilite")
@EntityScan(basePackages = {"fr.pivot.agilite", "fr.pivot.core.team"})
@EnableJpaRepositories(basePackages = {"fr.pivot.agilite", "fr.pivot.core.team"})
@EnableScheduling
// EN53.1 Vague 1 — @EnableWebSocketMessageBroker porté ICI (test-scope) : il a été retiré de
// fr.pivot.agilite.config.WebSocketConfig (un seul @Enable autorisé par contexte ; dans l'app
// agrégée c'est celui du shell, NotificationWebSocketConfig, qui le porte). Le contexte de test
// isolé d'agilite n'a pas le config du shell → sans ce @Enable ici, aucun bean d'infra STOMP
// (SimpMessagingTemplate) n'existe et les services WS (PokerTicketService, RetroCardService...)
// ne peuvent plus s'injecter. WebSocketConfig reste component-scanné comme configurer (broker,
// endpoints, interceptors). Jamais packagé dans le JAR de prod → aucun double @Enable une fois agrégé.
@EnableWebSocketMessageBroker
public class AgiliteTestApplication {
}
