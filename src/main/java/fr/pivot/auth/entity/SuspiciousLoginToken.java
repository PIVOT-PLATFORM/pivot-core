package fr.pivot.auth.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Single-use "Not me" link token emailed on a passive suspicious-login alert (US01.4.3a).
 *
 * <p>Shape mirrors {@link PasswordResetToken} (raw 256-bit value hashed with SHA-256, {@code
 * expiresAt}/{@code usedAt}) rather than {@link DeviceVerifyToken}'s 6-digit HMAC OTP: this
 * token is clicked from an email link, never typed in by hand. It carries the flagged device's
 * identifying details so {@code SuspiciousLoginService#confirmNotMe} can revoke exactly that
 * device's trust once the account owner re-authenticates with their current password.
 */
@Entity
@Table(name = "suspicious_login_tokens")
public class SuspiciousLoginToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "device_fingerprint", nullable = false, length = 64)
    private String deviceFingerprint;

    @Column(name = "device_name", length = 255)
    private String deviceName;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getDeviceFingerprint() { return deviceFingerprint; }
    public String getDeviceName() { return deviceName; }
    public String getIpAddress() { return ipAddress; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setUser(User user) { this.user = user; }
    public void setDeviceFingerprint(String deviceFingerprint) { this.deviceFingerprint = deviceFingerprint; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
}
