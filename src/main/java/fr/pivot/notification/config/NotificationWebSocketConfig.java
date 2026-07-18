package fr.pivot.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configuration STOMP over WebSocket — canal push des notifications in-app (EN-NOTIF AC :
 * {@code /user/{userId}/queue/notifications}).
 *
 * <p><strong>Endpoint :</strong> {@code /ws/notifications} (préfixé {@code /api} par
 * {@code server.servlet.context-path}, soit {@code /api/ws/notifications} côté client).
 *
 * <p><strong>Authentification :</strong> la poignée de main HTTP d'établissement du WebSocket ne
 * peut pas porter d'en-tête {@code Authorization} personnalisé (l'API {@code WebSocket} native
 * des navigateurs ne permet pas de définir des en-têtes arbitraires) — cet endpoint est donc
 * {@code permitAll()} côté {@code SecurityConfig}, et l'authentification réelle a lieu à la
 * première frame STOMP {@code CONNECT} (qui, elle, porte des en-têtes applicatifs arbitraires),
 * validée par {@link StompAuthChannelInterceptor}. Toute frame {@code CONNECT} sans token opaque
 * valide est rejetée — la session STOMP n'est jamais établie sans authentification.
 *
 * <p><strong>Fallback :</strong> si le canal WebSocket est indisponible (proxy/pare-feu
 * bloquant), le client peut se rabattre sur le polling {@code GET
 * /api/notifications/unread-count} toutes les 30 s — mécanisme documenté par l'AC EN-NOTIF,
 * indépendant de cette configuration.
 *
 * <p><strong>EN53.1 Vague 1 modulith merge (ADR-030) — {@code configureMessageBroker} retiré
 * d'ici.</strong> Dans l'app agrégée, cette classe partage son {@code ApplicationContext} avec
 * {@code fr.pivot.agilite.config.WebSocketConfig} (module agilite) : Spring invoque {@code
 * configureMessageBroker} sur CHAQUE {@code WebSocketMessageBrokerConfigurer} du contexte avec
 * LE MÊME {@code MessageBrokerRegistry}, et {@code enableSimpleBroker}/{@code
 * setApplicationDestinationPrefixes} écrasent (last-wins, pas de merge) tout appel précédent sur
 * ce registre. La configuration du broker et des préfixes est donc désormais centralisée dans
 * l'unique {@link fr.pivot.config.WebSocketBrokerTopologyConfig} (package {@code fr.pivot.config}
 * — voir sa JavaDoc pour le raisonnement complet), qui reprend telle quelle l'ancienne
 * configuration de cette classe ({@code enableSimpleBroker("/queue", "/topic")}, {@code
 * setUserDestinationPrefix("/user")}) et y ajoute l'union des préfixes de destination applicative
 * ({@code "/app"} + {@code "/app/agilite"}). Cette classe conserve {@code
 * @EnableWebSocketMessageBroker} (importe l'infrastructure STOMP — n'a pas besoin d'être
 * co-localisé avec la classe qui configure le registre), son endpoint {@code /ws/notifications}
 * et son intercepteur d'authentification, inchangés.
 */
@Configuration
@EnableWebSocketMessageBroker
public class NotificationWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final String allowedOrigins;

    /**
     * Construit la configuration avec ses collaborateurs.
     *
     * @param stompAuthChannelInterceptor authentifie chaque frame {@code CONNECT} par token opaque
     * @param allowedOrigins              origines autorisées, mêmes valeurs que
     *                                     {@code pivot.cors.allowed-origins} (CORS REST)
     */
    public NotificationWebSocketConfig(
            final StompAuthChannelInterceptor stompAuthChannelInterceptor,
            @Value("${pivot.cors.allowed-origins}") final String allowedOrigins) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(final StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/notifications")
                .setAllowedOriginPatterns(allowedOrigins.split(","));
    }

    @Override
    public void configureClientInboundChannel(final ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
