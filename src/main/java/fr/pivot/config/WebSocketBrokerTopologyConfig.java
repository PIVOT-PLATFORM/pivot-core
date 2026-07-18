package fr.pivot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Single authoritative STOMP message-broker + application-destination-prefix registration for
 * the aggregated {@code pivot-core-app} modulith (EN53.1 Vague 1 / EN53.2 Vague 2, ADR-030).
 *
 * <p><strong>The problem this class resolves.</strong> Before EN53.1, {@code
 * pivot-agilite-core} ran as its own Spring Boot application, so its {@code
 * fr.pivot.agilite.config.WebSocketConfig} was the only {@link WebSocketMessageBrokerConfigurer}
 * in its context and could freely call {@link MessageBrokerRegistry#enableStompBrokerRelay},
 * {@link MessageBrokerRegistry#enableSimpleBroker} and {@link
 * MessageBrokerRegistry#setApplicationDestinationPrefixes}. Same story for {@code
 * pivot-collaboratif-core} (EN53.2, Vague 2) and its own {@code
 * fr.pivot.collaboratif.config.WebSocketConfig}. In the aggregated modulith, {@code
 * fr.pivot.notification.config.NotificationWebSocketConfig} (shell, owner of {@code
 * @EnableWebSocketMessageBroker}), agilite's {@code WebSocketConfig} and collaboratif's {@code
 * WebSocketConfig} are ALL {@code WebSocketMessageBrokerConfigurer} beans in the SAME {@code
 * ApplicationContext}, and Spring's {@code DelegatingWebSocketMessageBrokerConfiguration} invokes
 * {@code configureMessageBroker} on every one of them against the SAME {@link
 * MessageBrokerRegistry} instance. None of the three calls above are additive across configurers
 * — each is last-write-wins on the registry (verified against {@code MessageBrokerRegistry}'s
 * source: {@code enableSimpleBroker}/{@code enableStompBrokerRelay} each simply overwrite the
 * registry's single stored registration field, and {@code setApplicationDestinationPrefixes}
 * overwrites its stored prefix list) — whichever configurer bean Spring happens to process last
 * would silently erase the other domains' broker topology, with no compile-time or startup-time
 * signal that anything was dropped.
 *
 * <p><strong>The fix: exactly one class owns these three calls in the aggregated app.</strong>
 * This class is that single owner. {@code NotificationWebSocketConfig} (shell), {@code
 * fr.pivot.agilite.config.WebSocketConfig} (agilite) and {@code
 * fr.pivot.collaboratif.config.WebSocketConfig} (collaboratif) no longer call any of the three
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
 *   <li>{@code fr.pivot.collaboratif.config.WebSocketConfig} is expected to follow the exact
 *       same gating pattern (EN53.2 Vague 2), behind an analogous {@code
 *       pivot.collaboratif.websocket.broker.self-managed} flag — see the "Collaboratif" section
 *       below for the destination-prefix analysis and the one open point this class's author
 *       could not verify directly (that class lives under {@code collaboratif/}, outside this
 *       task's file scope).</li>
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
 * <p><strong>Collaboratif (EN53.2 Vague 2) — no prefix change needed, verified against the
 * source repo.</strong> Inspecting {@code pivot-collaboratif-core}'s own {@code
 * fr.pivot.collaboratif.config.WebSocketConfig} (the pre-absorption standalone source, since the
 * ported copy under {@code collaboratif/} in this worktree is out of this class's file scope):
 * <ul>
 *   <li><strong>In-process room broker</strong> — {@code
 *       config.enableSimpleBroker("/topic/whiteboard", "/queue")}. {@code "/topic/whiteboard"}
 *       is a sub-destination of this class's own {@code "/topic"} registration — already
 *       subsumed by simple prefix matching, exactly like agilite's room prefixes above. No
 *       enumeration needed here.</li>
 *   <li><strong>EN07.3 cross-instance relay</strong> — {@code
 *       config.enableStompBrokerRelay(DOMAIN_RELAY_PREFIX)} with {@code DOMAIN_RELAY_PREFIX =
 *       "/topic/collaboratif."} (trailing dot). Same ADR-030 reasoning as agilite's own relay
 *       prefix: also a {@code "/topic"} sub-destination, and moot in the single-instance
 *       aggregated modulith once the shared {@code pivot.activemq.relay-enabled} flag is
 *       {@code false} (already the case in the aggregated app's {@code application.yml} — this
 *       key is shared verbatim by both agilite's and collaboratif's relay configuration, see
 *       that file).</li>
 *   <li><strong>Application destination prefix</strong> — {@code
 *       config.setApplicationDestinationPrefixes("/app")}, singular, no module-specific prefix
 *       (unlike agilite's {@code "/app/agilite"}). Confirmed against collaboratif's one {@code
 *       @MessageMapping} handler ({@code WhiteboardActionController#"/whiteboard/{boardId}/action"}
 *       ), which resolves to client destination {@code /app/whiteboard/{boardId}/action} — already
 *       covered by this class's existing {@code "/app"} entry. <strong>Conclusion: no addition
 *       to {@code setApplicationDestinationPrefixes} is required for collaboratif</strong> — the
 *       union stays {@code ("/app", "/app/agilite")}, not a three-way union.</li>
 * </ul>
 * <p><strong>Open point for human/CI verification</strong> (could not be checked directly — the
 * ported {@code collaboratif/} module is outside this class's file scope, another agent's
 * responsibility in EN53.2 Vague 2): the collaboratif module's ported {@code WebSocketConfig}
 * must (a) drop its {@code @EnableWebSocketMessageBroker} annotation, mirroring agilite's own
 * EN53.1 Vague 1 change (only one such annotation is permitted per {@code ApplicationContext};
 * {@code NotificationWebSocketConfig} already carries it), and (b) gate its {@code
 * configureMessageBroker} body behind a {@code pivot.collaboratif.websocket.broker.self-managed}
 * flag (default {@code true}, set to {@code false} in the aggregated app's {@code
 * application.yml}/{@code application-test.yml} — see those files for the corresponding config
 * this class's author added on that assumption). If the ported module instead kept an
 * unconditional {@code configureMessageBroker} (or a second {@code
 * @EnableWebSocketMessageBroker}), the last-write-wins collision this class exists to prevent
 * would resurface silently at runtime — no compile-time signal.
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
 * EN07.3 cross-instance fan-out and collaboratif's own EN07.3-equivalent domain bus (irrelevant
 * today with a single {@code pivot-core-app} instance, but would need re-enabling — {@code
 * pivot.activemq.relay-enabled: true} plus each module's {@code
 * *.websocket.broker.self-managed} flag reconciled — before any future horizontal scale-out of
 * the modulith). No compile-time check catches a future regression that reintroduces a
 * SECOND {@code configureMessageBroker} override elsewhere in the aggregated context; this
 * class's Javadoc and each module's own {@code WebSocketConfig} Javadoc are the documented
 * contract guarding against that.
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
