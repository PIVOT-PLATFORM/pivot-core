package fr.pivot.notification.repository;

import fr.pivot.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data repository pour {@link Notification} (EN-NOTIF).
 *
 * <p>Chaque méthode de lecture/écriture scope explicitement {@code userId} <em>et</em>
 * {@code tenantId} — défense en profondeur d'isolation tenant, même si {@code userId} seul
 * suffirait déjà en théorie (un utilisateur n'appartient qu'à un seul tenant).
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Liste paginée des notifications d'un utilisateur, scopée à son tenant.
     *
     * @param userId   identifiant de l'utilisateur, résolu depuis le token porteur
     * @param tenantId identifiant du tenant, résolu depuis le token porteur
     * @param pageable pagination (page/size/tri — {@code createdAt DESC} par défaut côté
     *                 contrôleur)
     * @return page des notifications correspondantes
     */
    Page<Notification> findByUserIdAndTenantId(Long userId, Long tenantId, Pageable pageable);

    /**
     * Compte les notifications non lues d'un utilisateur, scopée à son tenant.
     *
     * @param userId   identifiant de l'utilisateur
     * @param tenantId identifiant du tenant
     * @return nombre de notifications avec {@code readAt IS NULL}
     */
    long countByUserIdAndTenantIdAndReadAtIsNull(Long userId, Long tenantId);

    /**
     * Recherche une notification par identifiant, restreinte à son propriétaire — utilisé par
     * {@code PATCH /api/notifications/{id}/read} : un {@code id} appartenant à un autre
     * utilisateur (même tenant ou non) doit être indistinguable d'un {@code id} inexistant
     * (404, jamais 403 — voir CLAUDE.md « Isolation tenant »).
     *
     * @param id     identifiant technique de la notification (path variable, non fiable)
     * @param userId identifiant de l'utilisateur appelant, résolu depuis le token porteur
     * @return la notification si elle existe et appartient à {@code userId}, vide sinon
     */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    /**
     * Marque en une seule requête toutes les notifications non lues d'un utilisateur comme lues
     * — {@code PATCH /api/notifications/read-all}.
     *
     * @param userId   identifiant de l'utilisateur, résolu depuis le token porteur
     * @param tenantId identifiant du tenant, résolu depuis le token porteur
     * @param readAt   horodatage à appliquer aux lignes mises à jour
     * @return nombre de lignes effectivement mises à jour
     */
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :readAt "
            + "WHERE n.user.id = :userId AND n.tenant.id = :tenantId AND n.readAt IS NULL")
    int markAllAsRead(@Param("userId") Long userId, @Param("tenantId") Long tenantId, @Param("readAt") Instant readAt);
}
