package fr.pivot.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A pending "change my email" request (US02.2.2) — one row per confirmation link sent to a
 * candidate new address.
 *
 * <p>Mirrors {@link EmailVerification} / {@link PasswordResetToken}: the raw token is never
 * persisted, only its SHA-256 hash ({@link #tokenHash}). A row is single-use: {@link #usedAt}
 * is set on the first valid confirmation click, {@link #cancelledAt} is set when a newer
 * request supersedes it before it was ever confirmed. Either flag being non-null makes the
 * row terminal — a second confirmation attempt on it is rejected with 410 Gone.
 *
 * <p>{@code users.email} (the account's current, active address) is only overwritten once
 * confirmation succeeds — the old address stays fully functional for login until then.
 */
@Entity
@Table(name = "email_change_requests")
public class EmailChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "new_email", nullable = false, length = 320)
    private String newEmail;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getNewEmail() { return newEmail; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setUser(final User user) { this.user = user; }
    public void setNewEmail(final String newEmail) { this.newEmail = newEmail; }
    public void setTokenHash(final String tokenHash) { this.tokenHash = tokenHash; }
    public void setExpiresAt(final Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setUsedAt(final Instant usedAt) { this.usedAt = usedAt; }
    public void setCancelledAt(final Instant cancelledAt) { this.cancelledAt = cancelledAt; }
}
