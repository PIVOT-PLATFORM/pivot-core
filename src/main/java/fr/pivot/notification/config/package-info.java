/**
 * Configuration STOMP/WebSocket des notifications in-app (EN-NOTIF).
 *
 * <p>{@code @NullMarked} — tous les types de ce package sont non-nullables par défaut, sauf
 * annotation explicite {@code @Nullable} (JSpecify). Nécessaire pour que le contrat de
 * {@link fr.pivot.notification.config.StompAuthChannelInterceptor#preSend} s'aligne
 * correctement avec celui de {@code ChannelInterceptor#preSend} — lui-même déclaré
 * {@code @Nullable} en retour dans le package {@code @NullMarked}
 * {@code org.springframework.messaging.support}.
 */
@NullMarked
package fr.pivot.notification.config;

import org.jspecify.annotations.NullMarked;
