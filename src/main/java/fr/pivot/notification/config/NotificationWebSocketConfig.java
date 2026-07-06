package fr.pivot.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
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
    public void configureMessageBroker(final MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(final ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
