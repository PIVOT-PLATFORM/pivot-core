package fr.pivot.auth.entity;

import fr.pivot.tenant.entity.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(nullable = false, length = 50)
    private String role = "ROLE_USER";

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "google_id")
    private String googleId;

    @Column(name = "oidc_subject", length = 500)
    private String oidcSubject;

    /**
     * Preferred UI language (i18n). Defaults to French. {@code fr}/{@code en} only — enforced
     * by the {@code chk_users_locale} DB constraint (V4 migration). Used both to localize
     * transactional emails ({@code EmailService.toLocale}) and, since US02.1.2, exposed/
     * editable via the account profile API as {@code preferredLanguage} — single source of
     * truth for "the user's language", not one column per feature.
     */
    @Column(nullable = false, length = 10)
    private String locale = "fr";

    /** Profile photo URL provided by Google OAuth / OIDC ({@code picture} claim). */
    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_blocked", nullable = false)
    private boolean blocked = false;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "inactivity_warning_sent_at")
    private Instant inactivityWarningSentAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "scheduled_deletion_at")
    private Instant scheduledDeletionAt;

    /**
     * Set once {@code AccountDeletionScheduler} has anonymized this row (US02.2.4, RGPD Art.
     * 17), once {@link #scheduledDeletionAt} elapses. Distinguishes "pending, still within
     * grace period" ({@code deletedAt} set, this field {@code null}) from "purged" ({@code
     * null} check kept explicit rather than pattern-matching {@link #email} against the
     * {@code deleted-*@pivot.invalid} convention).
     */
    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Tenant getTenant() { return tenant; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getRole() { return role; }
    public boolean isEmailVerified() { return emailVerified; }
    public String getGoogleId() { return googleId; }
    public String getOidcSubject() { return oidcSubject; }
    public String getLocale() { return locale; }
    public String getAvatarUrl() { return avatarUrl; }
    public boolean isActive() { return active; }
    public boolean isBlocked() { return blocked; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getInactivityWarningSentAt() { return inactivityWarningSentAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public Instant getScheduledDeletionAt() { return scheduledDeletionAt; }
    public Instant getAnonymizedAt() { return anonymizedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setTenant(Tenant tenant) { this.tenant = tenant; }
    public void setEmail(String email) { this.email = email; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public void setRole(String role) { this.role = role; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public void setGoogleId(String googleId) { this.googleId = googleId; }
    public void setOidcSubject(String oidcSubject) { this.oidcSubject = oidcSubject; }
    public void setLocale(String locale) { this.locale = locale; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public void setActive(boolean active) { this.active = active; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public void setScheduledDeletionAt(Instant scheduledDeletionAt) { this.scheduledDeletionAt = scheduledDeletionAt; }
    public void setAnonymizedAt(Instant anonymizedAt) { this.anonymizedAt = anonymizedAt; }
    public void setInactivityWarningSentAt(Instant inactivityWarningSentAt) { this.inactivityWarningSentAt = inactivityWarningSentAt; }
}
