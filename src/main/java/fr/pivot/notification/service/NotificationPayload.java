package fr.pivot.notification.service;

import java.util.Arrays;
import java.util.List;

/**
 * Arguments de substitution passés à {@link org.springframework.context.MessageSource} pour
 * résoudre le titre/corps d'une notification (voir {@link NotificationType}, format
 * {@code {0}}, {@code {1}}… de {@code java.text.MessageFormat}).
 *
 * <p>Paramètre {@code payload} de {@code NotificationService.create(userId, type, payload)}
 * (EN-NOTIF AC) — un simple porteur de valeurs déjà résolues par l'appelant (ex. le nouveau rôle
 * pour {@link NotificationType#ROLE_CHANGED}), jamais de logique métier.
 *
 * @param args arguments positionnels, dans l'ordre attendu par les clés
 *             {@code messages.properties} du {@link NotificationType} concerné
 */
public record NotificationPayload(List<Object> args) {

    /**
     * Copie défensive de {@code args} (SpotBugs EI_EXPOSE_REP2 / EI_EXPOSE_REP).
     *
     * @param args arguments positionnels
     */
    public NotificationPayload {
        args = List.copyOf(args);
    }

    /**
     * Construit un {@link NotificationPayload} à partir d'arguments variadiques — forme d'appel
     * la plus lisible côté producteur (ex. {@code NotificationPayload.of(role.name())}).
     *
     * @param args arguments positionnels, éventuellement aucun
     * @return payload immuable
     */
    public static NotificationPayload of(final Object... args) {
        return new NotificationPayload(Arrays.asList(args));
    }

    /**
     * Vue tableau des arguments, seule forme acceptée par
     * {@link org.springframework.context.MessageSource#getMessage(String, Object[], java.util.Locale)}.
     *
     * @return copie tableau des arguments positionnels
     */
    public Object[] toArray() {
        return args.toArray();
    }
}
