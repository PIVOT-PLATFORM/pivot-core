package fr.pivot.notification.config;

import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Authentifie chaque frame STOMP {@code CONNECT} par token opaque (EN-NOTIF).
 *
 * <p>La poignée de main HTTP d'établissement du WebSocket est {@code permitAll()} (voir
 * {@link NotificationWebSocketConfig}) — cet intercepteur est donc l'unique point
 * d'authentification réel du canal. Une frame {@code CONNECT} sans en-tête natif
 * {@code Authorization: Bearer <token>} valide (résolu via {@link
 * AuthenticatedPrincipalResolver#resolve}, exactement comme {@code
 * fr.pivot.config.TokenAuthenticationFilter} pour les requêtes REST) fait échouer
 * l'établissement de la session STOMP — {@code MessagingException} propagée par Spring en frame
 * {@code ERROR}, connexion fermée.
 *
 * <p>Dépend de {@link AuthenticatedPrincipalResolver} (ADR-022, {@code pivot-core#171} EN17.1
 * volet {@code fr.pivot.core.auth}) plutôt que de {@code fr.pivot.auth.service.TokenService}/
 * {@code fr.pivot.auth.entity.User} concrets — cet intercepteur n'a jamais eu besoin de plus que
 * l'identité minimale ({@code userId}), jamais du profil complet. En pratique, Spring résout la
 * même instance de bean ({@code TokenService}, seule implémentation), comportement inchangé.
 *
 * <p>Le principal STOMP posé sur la session ({@code accessor.setUser}) porte
 * {@code String.valueOf(principal.userId())} comme {@code getName()} — c'est cette valeur que
 * {@code SimpMessagingTemplate#convertAndSendToUser(String, String, Object)} doit recevoir en
 * premier argument pour router vers {@code /user/{userId}/queue/notifications}.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthenticatedPrincipalResolver principalResolver;

    /**
     * Construit l'intercepteur avec son collaborateur.
     *
     * @param principalResolver résolution du token opaque vers l'identité minimale — même
     *                          implémentation ({@code TokenService}) que la couche REST
     */
    public StompAuthChannelInterceptor(final AuthenticatedPrincipalResolver principalResolver) {
        this.principalResolver = principalResolver;
    }

    @Override
    public @Nullable Message<?> preSend(final Message<?> message, final MessageChannel channel) {
        final StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            final String rawToken = extractBearerToken(accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION));
            final AuthenticatedPrincipal principal = principalResolver.resolve(rawToken).orElse(null);

            if (principal == null) {
                LOG.warn("event=STOMP_CONNECT_REJECTED reason=invalid_or_missing_token");
                throw new MessagingException("Invalid or missing bearer token on STOMP CONNECT");
            }

            final String principalName = String.valueOf(principal.userId());
            accessor.setUser(() -> principalName);
            LOG.debug("event=STOMP_CONNECT_AUTHENTICATED userId={}", principal.userId());
        }

        return message;
    }

    /**
     * Extrait le token brut d'un en-tête {@code Authorization: Bearer <token>}.
     *
     * @param header valeur brute de l'en-tête natif STOMP {@code Authorization}, potentiellement
     *               {@code null}
     * @return le token brut, ou {@code null} si l'en-tête est absent ou mal formé
     */
    private static @Nullable String extractBearerToken(final @Nullable String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }
}
