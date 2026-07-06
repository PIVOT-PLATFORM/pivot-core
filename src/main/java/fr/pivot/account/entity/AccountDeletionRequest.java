package fr.pivot.account.entity;

import fr.pivot.auth.entity.User;
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
 * One row per account-deletion request (US02.2.4, RGPD Art. 17) — kept forever (with {@link
 * #cancelledAt} set) even after cancellation or purge, as the accountability trail for an
 * irreversible action.
 *
 * <p>{@code users.deleted_at} / {@code users.scheduled_deletion_at} carry the live
 * PENDING_DELETION state consumed by login/admin-read paths; this row exists alongside them
 * purely to hold the single-use cancellation token and the confirmation method used, without
 * cluttering the {@link User} entity with fields only relevant while a deletion is in flight.
 */
@Entity
@Table(name = "account_deletion_requests")
public class AccountDeletionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "confirmed_via", nullable = false, length = 10)
    private DeletionConfirmationMethod confirmedVia;

    @Column(name = "cancel_token_hash", nullable = false, unique = true, length = 64)
    private String cancelTokenHash;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public User getUser() { return user; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public DeletionConfirmationMethod getConfirmedVia() { return confirmedVia; }
    public String getCancelTokenHash() { return cancelTokenHash; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setUser(final User user) { this.user = user; }
    public void setRequestedAt(final Instant requestedAt) { this.requestedAt = requestedAt; }
    public void setEffectiveAt(final Instant effectiveAt) { this.effectiveAt = effectiveAt; }
    public void setConfirmedVia(final DeletionConfirmationMethod confirmedVia) { this.confirmedVia = confirmedVia; }
    public void setCancelTokenHash(final String cancelTokenHash) { this.cancelTokenHash = cancelTokenHash; }
    public void setCancelledAt(final Instant cancelledAt) { this.cancelledAt = cancelledAt; }
}
