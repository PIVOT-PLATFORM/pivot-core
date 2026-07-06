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
 * Email OTP confirmation for an account-deletion request on an account with no local password
 * (auth_mode OIDC / Google-only) — US02.2.4.
 *
 * <p>Mirrors {@link fr.pivot.auth.entity.DeviceVerifyToken} (US01.4.1): 6-digit OTP,
 * HMAC-SHA256 hash (never the raw code), bounded wrong-attempt count, short TTL. A dedicated
 * table rather than reusing {@code device_verify_tokens} directly — there is no
 * device/fingerprint concept for a deletion confirmation.
 */
@Entity
@Table(name = "account_deletion_otps")
public class AccountDeletionOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "otp_hash", nullable = false, length = 64)
    private String otpHash;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getOtpHash() { return otpHash; }
    public int getAttempts() { return attempts; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isExpired() { return expiresAt.isBefore(Instant.now()); }
    public boolean isConfirmed() { return confirmedAt != null; }

    public void setUser(final User user) { this.user = user; }
    public void setOtpHash(final String otpHash) { this.otpHash = otpHash; }
    public void setAttempts(final int attempts) { this.attempts = attempts; }
    public void setExpiresAt(final Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setConfirmedAt(final Instant confirmedAt) { this.confirmedAt = confirmedAt; }
}
