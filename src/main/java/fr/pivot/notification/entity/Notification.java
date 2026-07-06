package fr.pivot.notification.entity;

import fr.pivot.auth.entity.User;
import fr.pivot.notification.service.NotificationType;
import fr.pivot.tenant.entity.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Notification in-app persistée pour un utilisateur (EN-NOTIF).
 *
 * <p>{@code tenant} est dénormalisé depuis {@code user.getTenant()} au moment de la création
 * ({@code NotificationService#create}) — jamais accepté depuis l'appelant. Voir
 * {@code V1__schema_init.sql} (table {@code notifications}) pour le détail du choix de
 * dénormalisation (filtrage direct + défense en profondeur isolation tenant).
 *
 * <p>Ne jamais exposer cette entité directement en API — voir
 * {@link fr.pivot.notification.dto.NotificationDto}.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(final User user) {
        this.user = user;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(final Tenant tenant) {
        this.tenant = tenant;
    }

    public NotificationType getType() {
        return type;
    }

    public void setType(final NotificationType type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(final String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(final String body) {
        this.body = body;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(final Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * {@code true} si cette notification n'a pas encore été marquée comme lue.
     *
     * @return {@code true} si {@code readAt} est {@code null}
     */
    public boolean isUnread() {
        return readAt == null;
    }
}
