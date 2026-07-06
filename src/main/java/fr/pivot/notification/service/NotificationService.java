package fr.pivot.notification.service;

import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.notification.dto.NotificationDto;
import fr.pivot.notification.entity.Notification;
import fr.pivot.notification.event.NotificationCreatedEvent;
import fr.pivot.notification.exception.NotificationNotFoundException;
import fr.pivot.notification.repository.NotificationRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * Infrastructure notifications in-app (EN-NOTIF) — création, lecture paginée et marquage comme
 * lue, avec isolation tenant systématique.
 *
 * <p><strong>Producteurs</strong> — voir {@link NotificationType} pour le détail (deux câblés
 * dès cet enabler : US06.1.3, US06.1.4 ; deux définis par anticipation, pas encore émis :
 * US01.5.1, US01.4.3a).
 *
 * <p><strong>Push</strong> — {@link #create} publie un {@link NotificationCreatedEvent} après
 * la persistance ; {@code NotificationPushListener} le consomme en
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} pour le push STOMP
 * ({@code /user/{userId}/queue/notifications}). Ce découplage garantit qu'aucun push n'est
 * envoyé pour une notification dont la transaction serait finalement annulée, et qu'un échec de
 * livraison WebSocket ne fait jamais échouer la création elle-même — {@code GET
 * /api/notifications/unread-count} (polling 30s côté client) reste le filet de sécurité en cas
 * d'indisponibilité du canal WebSocket, conformément à l'AC EN-NOTIF.
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final MessageSource messageSource;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Construit le service avec ses collaborateurs.
     *
     * @param notificationRepository accès aux notifications persistées
     * @param userRepository         résolution du destinataire (tenant, locale) à la création
     * @param messageSource          résolution i18n des titres/corps (voir {@link NotificationType})
     * @param eventPublisher         publication de {@link NotificationCreatedEvent} pour le push STOMP
     */
    public NotificationService(
            final NotificationRepository notificationRepository,
            final UserRepository userRepository,
            final MessageSource messageSource,
            final ApplicationEventPublisher eventPublisher) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.messageSource = messageSource;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Crée une notification pour un utilisateur (EN-NOTIF AC — {@code create(userId, type,
     * payload)}).
     *
     * <p>{@code tenantId} n'est jamais un paramètre de cette méthode : il est dérivé de
     * {@code user.getTenant()} — la seule source de vérité — jamais fourni par l'appelant (voir
     * CLAUDE.md « Isolation tenant »). Titre et corps sont résolus <strong>une fois, à la
     * création</strong>, dans la locale du destinataire ({@code user.getLocale()}) et persistés
     * tels quels : un changement ultérieur de {@code messages.properties} ou de la locale de
     * l'utilisateur n'altère jamais rétroactivement une notification déjà créée.
     *
     * @param userId  identifiant du destinataire
     * @param type    type de notification (détermine les clés {@code messages.properties})
     * @param payload arguments de substitution du message (voir {@link NotificationPayload})
     * @return l'entité {@link Notification} persistée
     * @throws IllegalArgumentException si {@code userId} ne correspond à aucun utilisateur —
     *     ne devrait jamais se produire pour un producteur interne appelant avec un
     *     {@code userId} déjà résolu depuis la base
     */
    @Transactional
    public Notification create(final Long userId, final NotificationType type, final NotificationPayload payload) {
        final User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown userId: " + userId));

        final Locale locale = toLocale(user.getLocale());
        final Object[] args = payload.toArray();
        final String title = messageSource.getMessage(type.titleKey(), args, locale);
        final String body = messageSource.getMessage(type.bodyKey(), args, locale);

        final Notification notification = new Notification();
        notification.setUser(user);
        notification.setTenant(user.getTenant());
        notification.setType(type);
        notification.setTitle(title);
        notification.setBody(body);

        final Notification saved = notificationRepository.save(notification);

        eventPublisher.publishEvent(new NotificationCreatedEvent(user.getId(), NotificationDto.from(saved)));

        return saved;
    }

    /**
     * Liste paginée des notifications d'un utilisateur, triée {@code createdAt DESC} par défaut
     * côté contrôleur (EN-NOTIF AC — {@code GET /api/notifications?page=&size=}).
     *
     * @param userId   identifiant de l'utilisateur authentifié, résolu depuis le token porteur
     * @param tenantId identifiant du tenant, résolu depuis le token porteur — jamais du corps ou
     *                 d'un paramètre de requête
     * @param pageable pagination demandée
     * @return page de {@link NotificationDto} — jamais l'entité JPA directement
     */
    @Transactional(readOnly = true)
    public Page<NotificationDto> list(final Long userId, final Long tenantId, final Pageable pageable) {
        return notificationRepository.findByUserIdAndTenantId(userId, tenantId, pageable)
                .map(NotificationDto::from);
    }

    /**
     * Compte les notifications non lues d'un utilisateur (EN-NOTIF AC — {@code GET
     * /api/notifications/unread-count}).
     *
     * @param userId   identifiant de l'utilisateur authentifié
     * @param tenantId identifiant du tenant, résolu depuis le token porteur
     * @return nombre de notifications non lues
     */
    @Transactional(readOnly = true)
    public long unreadCount(final Long userId, final Long tenantId) {
        return notificationRepository.countByUserIdAndTenantIdAndReadAtIsNull(userId, tenantId);
    }

    /**
     * Marque une notification comme lue (EN-NOTIF AC — {@code markAsRead(notificationId,
     * userId)}).
     *
     * <p>Idempotent : marquer une notification déjà lue ne modifie pas {@code readAt} (conserve
     * l'horodatage de première lecture) et ne lève aucune erreur.
     *
     * @param notificationId identifiant de la notification ciblée (path variable, non fiable)
     * @param userId         identifiant de l'utilisateur appelant, résolu depuis le token
     *                       porteur — seule vérification d'appartenance (un utilisateur
     *                       n'appartenant qu'à un seul tenant, elle implique l'isolation tenant)
     * @return la notification mise à jour
     * @throws NotificationNotFoundException si {@code notificationId} n'existe pas ou
     *     n'appartient pas à {@code userId}
     */
    @Transactional
    public NotificationDto markAsRead(final Long notificationId, final Long userId) {
        final Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));

        if (notification.isUnread()) {
            notification.setReadAt(Instant.now());
            notificationRepository.save(notification);
        }

        return NotificationDto.from(notification);
    }

    /**
     * Marque toutes les notifications non lues d'un utilisateur comme lues en une seule requête
     * (EN-NOTIF AC — {@code PATCH /api/notifications/read-all}).
     *
     * @param userId   identifiant de l'utilisateur authentifié
     * @param tenantId identifiant du tenant, résolu depuis le token porteur
     * @return nombre de notifications effectivement passées de non-lue à lue
     */
    @Transactional
    public int markAllAsRead(final Long userId, final Long tenantId) {
        return notificationRepository.markAllAsRead(userId, tenantId, Instant.now());
    }

    /**
     * Convertit un tag de langue (ex. {@code "fr"}, {@code "en"}) en {@link Locale}, par défaut
     * français — même convention que {@code EmailService#toLocale}, dupliquée volontairement ici
     * (fonction pure à trois lignes) plutôt que de faire dépendre {@code fr.pivot.notification}
     * de {@code fr.pivot.auth.service} pour une utilitaire sans rapport avec l'email.
     *
     * @param localeTag tag de langue brut ({@code users.locale}), potentiellement {@code null}
     * @return {@link Locale#FRENCH} si {@code localeTag} est {@code null} ou différent de
     *     {@code "en"}, {@link Locale#ENGLISH} sinon
     */
    private static Locale toLocale(final String localeTag) {
        if (localeTag == null) {
            return Locale.FRENCH;
        }
        return "en".equals(localeTag) ? Locale.ENGLISH : Locale.FRENCH;
    }
}
