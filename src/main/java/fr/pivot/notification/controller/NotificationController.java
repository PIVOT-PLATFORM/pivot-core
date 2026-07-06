package fr.pivot.notification.controller;

import fr.pivot.auth.entity.User;
import fr.pivot.notification.dto.MarkAllReadResponse;
import fr.pivot.notification.dto.NotificationDto;
import fr.pivot.notification.dto.UnreadCountResponse;
import fr.pivot.notification.exception.NotificationNotFoundException;
import fr.pivot.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller des notifications in-app de l'utilisateur authentifié (EN-NOTIF).
 *
 * <p>Mapping {@code /notifications} (sans préfixe {@code /api}, ajouté par
 * {@code server.servlet.context-path=/api}) — même convention que
 * {@code fr.pivot.tenant.api.SuperAdminTenantController}.
 *
 * <p>Aucune logique métier ici — délégation intégrale à {@link NotificationService}.
 *
 * <p><strong>Isolation :</strong> {@code userId} et {@code tenantId} ne sont jamais acceptés
 * depuis un paramètre de requête, le corps ou un en-tête — uniquement depuis l'entité
 * {@link User} posée par {@code fr.pivot.config.TokenAuthenticationFilter} dans les détails de
 * l'authentification courante (même schéma que {@code AdminUserController}). Chaque endpoint
 * n'opère donc jamais que sur les notifications de l'appelant, dans son propre tenant.
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationService notificationService;

    /**
     * Construit le contrôleur avec son collaborateur.
     *
     * @param notificationService service métier des notifications in-app
     */
    public NotificationController(final NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Liste paginée des notifications de l'utilisateur authentifié, triées {@code createdAt
     * DESC} par défaut.
     *
     * @param pageable pagination — défaut {@code page=0}, {@code size=20}, tri
     *                 {@code createdAt DESC} · {@code size} plafonné globalement par
     *                 {@link fr.pivot.config.PaginationConfig}
     * @return {@code 200} avec une page Spring Data de {@link NotificationDto} · {@code 401} si
     *     le contexte d'authentification est invalide
     */
    @GetMapping
    public ResponseEntity<Page<NotificationDto>> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) final Pageable pageable) {
        final User actor = resolveActor();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final Page<NotificationDto> page =
                notificationService.list(actor.getId(), actor.getTenant().getId(), pageable);
        return ResponseEntity.ok(page);
    }

    /**
     * Nombre de notifications non lues de l'utilisateur authentifié.
     *
     * @return {@code 200} avec {@link UnreadCountResponse} · {@code 401} si le contexte
     *     d'authentification est invalide
     */
    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount() {
        final User actor = resolveActor();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final long count = notificationService.unreadCount(actor.getId(), actor.getTenant().getId());
        return ResponseEntity.ok(new UnreadCountResponse(count));
    }

    /**
     * Marque une notification de l'utilisateur authentifié comme lue.
     *
     * @param id identifiant de la notification ciblée (path variable — jamais le corps)
     * @return {@code 200} avec le {@link NotificationDto} mis à jour · {@code 401} si le
     *     contexte d'authentification est invalide · {@code 404} si {@code id} n'existe pas ou
     *     n'appartient pas à l'appelant
     */
    @PatchMapping("/{id}/read")
    public ResponseEntity<NotificationDto> markAsRead(@PathVariable("id") final Long id) {
        final User actor = resolveActor();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final NotificationDto updated = notificationService.markAsRead(id, actor.getId());
        return ResponseEntity.ok(updated);
    }

    /**
     * Marque toutes les notifications non lues de l'utilisateur authentifié comme lues.
     *
     * @return {@code 200} avec {@link MarkAllReadResponse} (nombre de notifications mises à
     *     jour) · {@code 401} si le contexte d'authentification est invalide
     */
    @PatchMapping("/read-all")
    public ResponseEntity<MarkAllReadResponse> markAllAsRead() {
        final User actor = resolveActor();
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final int updated = notificationService.markAllAsRead(actor.getId(), actor.getTenant().getId());
        return ResponseEntity.ok(new MarkAllReadResponse(updated));
    }

    // ----------------------------------------------------------------
    // Exception handling — local à ce contrôleur (pas de handler global)
    // ----------------------------------------------------------------

    /**
     * Traduit une notification introuvable ou n'appartenant pas à l'appelant en {@code 404 Not
     * Found}.
     *
     * @param ex l'exception levée par le service
     * @return corps d'erreur {@code 404}
     */
    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(final NotificationNotFoundException ex) {
        LOG.warn("event=NOTIFICATION_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "NOTIFICATION_NOT_FOUND",
                "message", "Cette notification n'existe pas"));
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    /**
     * Résout l'utilisateur authentifié depuis le contexte de sécurité.
     *
     * <p>{@code tenantId}/{@code userId} ne sont jamais lus ailleurs que dans l'entité
     * {@link User} posée par le filtre d'authentification — jamais depuis le corps, un
     * paramètre ou un en-tête.
     *
     * @return l'utilisateur authentifié, ou {@code null} si le contexte d'authentification est
     *     invalide ou si l'utilisateur n'appartient à aucun tenant
     */
    private User resolveActor() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getDetails() instanceof User user)) {
            LOG.warn("event=NOTIFICATIONS_REJECTED reason=invalid_auth_details type={}",
                    auth == null || auth.getDetails() == null ? "null" : auth.getDetails().getClass().getName());
            return null;
        }

        if (user.getTenant() == null) {
            LOG.warn("event=NOTIFICATIONS_REJECTED reason=no_tenant userId={}", user.getId());
            return null;
        }

        return user;
    }
}
