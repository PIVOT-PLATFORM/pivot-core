package fr.pivot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Single authoritative STOMP message-broker + application-destination-prefix registration for
 * the aggregated {@code pivot-core-app} modulith (EN53.1 Vague 1, ADR-030).
 *
 * <p><strong>The problem this class resolves.</strong> Before EN53.1, {@code
 * pivot-agilite-core} ran as its own Spring Boot application, so its {@code
 * fr.pivot.agilite.config.WebSocketConfig} was the only {@link WebSocketMessageBrokerConfigurer}
 * in its context and could freely call {@link MessageBrokerRegistry#enableStompBrokerRelay},
 * {@link MessageBrokerRegistry#enableSimpleBroker} and {@link
 * MessageBrokerRegistry#setApplicationDestinationPrefixes}. In the aggregated modulith, {@code
 * fr.pivot.notification.config.NotificationWebSocketConfig} (shell, owner of {@code
 * @EnableWebSocketMessageBroker}) and agilite's {@code WebSocketConfig} are BOTH {@code
 * WebSocketMessageBrokerConfigurer} beans in the SAME {@code ApplicationContext}, and Spring's
 * {@code DelegatingWebSocketMessageBrokerConfiguration} invokes {@code configureMessageBroker}
 * on every one of them against the SAME {@link MessageBrokerRegistry} instance. None of the
 * three calls above are additive across configurers — each is last-write-wins on the registry
 * (verified against {@code MessageBrokerRegistry}'s source: {@code enableSimpleBroker}/{@code
 * enableStompBrokerRelay} each simply overwrite the registry's single stored registration field,
 * and {@code setApplicationDestinationPrefixes} overwrites its stored prefix list) — whichever
 * configurer bean Spring happens to process last would silently erase the other domain's broker
 * topology, with no compile-time or startup-time signal that anything was dropped.
 *
 * <p><strong>The fix: exactly one class owns these three calls in the aggregated app.</strong>
 * This class is that single owner. Both {@code NotificationWebSocketConfig} (shell) and {@code
 * fr.pivot.agilite.config.WebSocketConfig} (agilite) no longer call any of the three
 * registry-mutating methods when running inside {@code pivot-core-app}:
 * <ul>
 *   <li>{@code NotificationWebSocketConfig} had its {@code configureMessageBroker} override
 *       removed entirely (EN53.1) — this class absorbs its exact prior registration
 *       ({@code enableSimpleBroker("/queue", "/topic")}, {@code setUserDestinationPrefix("/user")})
 *       verbatim, so the notification channel's behaviour is unchanged.</li>
 *   <li>{@code fr.pivot.agilite.config.WebSocketConfig} keeps its own full {@code
 *       configureMessageBroker} implementation (needed for its module's isolated test context,
 *       {@code AgiliteTestApplication}/{@code WebSocketConfigIT}, which never sees this class —
 *       it lives in {@code fr.pivot.config}, outside {@code fr.pivot.agilite}'s component scan),
 *       but that implementation is now gated behind {@code
 *       pivot.agilite.websocket.broker.self-managed} (default {@code true}). The aggregated
 *       app's {@code application.yml}/{@code application-test.yml} set this to {@code false},
 *       turning agilite's {@code configureMessageBroker} into a no-op there — endpoints,
 *       interceptors and the transport decorator it also registers are untouched (those callback
 *       methods DO accumulate across configurers with no conflict, see their own JavaDoc).</li>
 * </ul>
 *
 * <p><strong>Why the registration below already covers agilite's traffic — no relay, by
 * design (ADR-030).</strong> ADR-030 (modulith migration) makes the ActiveMQ STOMP broker relay
 * optional in the modulith, favouring in-process delivery. This class therefore never calls
 * {@code enableStompBrokerRelay} at all:
 * <ul>
 *   <li>{@code enableSimpleBroker("/queue", "/topic")} — the {@code "/topic"} prefix is matched
 *       by simple {@code String#startsWith}, so it already subsumes every agilite room
 *       destination ({@code /topic/agilite/poker/*}, {@code /topic/agilite/retro/*}, {@code
 *       /topic/agilite/wheels/*} — see {@code fr.pivot.agilite.config.WebSocketConfig
 *       .ROOM_BROKER_PREFIXES}) without needing to enumerate them here. Agilite's EN07.3
 *       cross-instance relay prefix ({@code /topic/agilite.}, trailing dot) is likewise a {@code
 *       "/topic"} sub-destination — in the aggregated single-instance modulith there is no
 *       second JVM to relay to, so this in-process broker is sufficient and the ambiguity
 *       described in EN53.1's original problem statement (SimpleBroker {@code "/topic"}
 *       overlapping the relay's {@code "/topic/agilite."}) is moot once the relay itself is
 *       disabled (see {@code pivot.activemq.relay-enabled: false} in the aggregated app's
 *       {@code application.yml} — kept configurable per-environment for a future
 *       horizontal-scaling need, not hard-removed).</li>
 *   <li>{@code setApplicationDestinationPrefixes("/app", "/app/agilite")} — a union, not a
 *       choice: this registry method accepts multiple prefixes natively (unlike {@code
 *       enableSimpleBroker}/{@code enableStompBrokerRelay}, which are mutually exclusive broker
 *       backends, this call is simply additive over the given varargs). {@code "/app"} routes
 *       to the shell's own {@code @MessageMapping} handlers, {@code "/app/agilite"} to agilite's
 *       ({@code PokerRoomController}-equivalent {@code @MessageMapping}s, etc.) — both are
 *       required simultaneously.</li>
 *   <li>{@code setUserDestinationPrefix("/user")} — shell-only (EN-NOTIF {@code
 *       /user/{userId}/queue/notifications}); agilite never sets this, so there is nothing to
 *       union here.</li>
 * </ul>
 *
 * <p><strong>Why this class does not itself carry {@code @EnableWebSocketMessageBroker}.</strong>
 * That annotation only needs to appear on ONE {@code @Configuration} class in the context to
 * import the STOMP messaging infrastructure ({@code SimpAnnotationMethodMessageHandler}, {@code
 * brokerChannel}, {@code clientInboundChannel}/{@code clientOutboundChannel}, {@code
 * stompWebSocketHandlerMapping}, ...); it does not need to be co-located with the class that
 * happens to configure the registry. Keeping it on {@code NotificationWebSocketConfig} (where it
 * already lived pre-EN53.1) minimises the diff and risk of this change — Spring's {@code
 * DelegatingWebSocketMessageBrokerConfiguration} still discovers and invokes every {@code
 * WebSocketMessageBrokerConfigurer} bean in the context, including this one, regardless of which
 * bean is annotated with {@code @EnableWebSocketMessageBroker}.
 *
 * <p><strong>Risk flagged for CI / human review — realtime behaviour change.</strong> Disabling
 * the ActiveMQ relay by default in the aggregated app is a behavioural change for agilite's
 * EN07.3 cross-instance fan-out (irrelevant today with a single {@code pivot-core-app} instance,
 * but would need re-enabling — {@code pivot.activemq.relay-enabled: true} plus {@code
 * pivot.agilite.websocket.broker.self-managed} reconciled — before any future horizontal scale-
 * out of the modulith). No compile-time check catches a future regression that reintroduces a
 * SECOND {@code configureMessageBroker} override elsewhere in the aggregated context; this
 * class's Javadoc and agilite's own {@code WebSocketConfig} Javadoc are the documented contract
 * guarding against that.
 */
@Configuration
public class WebSocketBrokerTopologyConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(final MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app", "/app/agilite");
        registry.setUserDestinationPrefix("/user");
    }
}
