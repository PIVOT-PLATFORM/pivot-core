package fr.pivot.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Represents a DB-backed opaque session token (US-AUTH-002).
 *
 * <p>Replaces the previous JWT / refresh-token dual pattern.
 * One {@code AccessToken} per active session — it acts as both the bearer credential
 * (validated on every API request) and the session persistence mechanism (via httpOnly cookie).
 *
 * <p>The raw token (256 bits of {@link java.security.SecureRandom}, hex-encoded to 64 chars)
 * is never persisted — only its SHA-256 hash ({@code token_hash}).
 *
 * <p>TTL and auto-refresh threshold are admin-configurable via {@code feature_flags}:
 * <ul>
 *   <li>{@code SESSION_TTL_SECONDS} — default 86400 (24 h)</li>
 *   <li>{@code SESSION_TTL_REMEMBER_ME_SECONDS} — default 2592000 (30 days)</li>
 *   <li>{@code SESSION_REFRESH_THRESHOLD} — default 0.15 (refresh in final 15% of TTL)</li>
 * </ul>
 */
@Entity
@Table(name = "access_tokens")
public class AccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 hex-encoded hash of the raw token — never store the raw value. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "device_fingerprint", length = 64)
    private String deviceFingerprint;

    @Column(name = "device_name", length = 255)
    private String deviceName;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /** Authentication method used: {@code PASSWORD}, {@code GOOGLE}, or {@code OIDC}. */
    @Column(name = "auth_method", nullable = false, length = 20)
    private AuthMethod authMethod = AuthMethod.PASSWORD;

    /** {@code true} if the user selected «Se souvenir de moi» at login. */
    @Column(name = "remember_me", nullable = false)
    private boolean rememberMe = false;

    /**
     * Token lifecycle status.
     *
     * <ul>
     *   <li>{@code ACTIVE} — valid, can authenticate requests</li>
     *   <li>{@code EXPIRED} — past {@code expires_at}, set by validation filter</li>
     *   <li>{@code REVOKED} — explicitly revoked (logout, password change)</li>
     * </ul>
     */
    @Column(name = "status", nullable = false, length = 10)
    private TokenStatus status = TokenStatus.ACTIVE;

    /**
     * TTL captured from {@code feature_flags} at creation time.
     * Stored here to avoid querying feature_flags on every token validation.
     */
    @Column(name = "ttl_seconds", nullable = false)
    private int ttlSeconds;

    /** Updated on every validated request — used for session activity reporting. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** Absolute expiry timestamp, computed as {@code created_at + ttl_seconds}. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Timestamp when the token was revoked — null if still active or expired. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * Timestamp when this token was rotated — null until rotation. A rotated token stays
     * {@code ACTIVE} for a short grace window so in-flight concurrent requests still
     * authenticate; {@link #needsRefresh(double)} returns {@code false} once set to prevent
     * repeated re-rotation.
     */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    // ----------------------------------------------------------------
    // Domain logic
    // ----------------------------------------------------------------

    /**
     * Returns {@code true} if the token is active and not yet expired.
     *
     * @return {@code true} when status is {@code ACTIVE} and expiry is in the future
     */
    public boolean isValid() {
        return TokenStatus.ACTIVE.equals(status) && expiresAt.isAfter(Instant.now());
    }

    /**
     * Returns {@code true} if remaining TTL is below the given threshold ratio.
     *
     * <p>Used by {@code TokenAuthenticationFilter} to decide whether to issue a new token.
     * Example: {@code threshold = 0.5} triggers refresh when less than 50% of TTL remains.
     *
     * @param threshold fraction of total TTL (0.0–1.0)
     * @return {@code true} when the remaining TTL fraction is less than {@code threshold}
     */
    public boolean needsRefresh(final double threshold) {
        // Already rotated: an in-flight request is using the old token within its grace
        // window — do not trigger another rotation (prevents the re-rotation storm).
        if (rotatedAt != null) {
            return false;
        }
        final long remainingSeconds = java.time.Duration.between(Instant.now(), expiresAt).getSeconds();
        if (remainingSeconds < 0) {
            return false;
        }
        return (double) remainingSeconds / ttlSeconds < threshold;
    }

    // ----------------------------------------------------------------
    // Accessors
    // ----------------------------------------------------------------

    /** @return database primary key */
    public Long getId() { return id; }

    /** @return the authenticated user who owns this session token */
    public User getUser() { return user; }

    /** @return SHA-256 hash of the raw token */
    public String getTokenHash() { return tokenHash; }

    /** @return device fingerprint associated with this session */
    public String getDeviceFingerprint() { return deviceFingerprint; }

    /** @return human-readable device label */
    public String getDeviceName() { return deviceName; }

    /** @return browser user-agent string */
    public String getUserAgent() { return userAgent; }

    /** @return client IP address (IPv4 or IPv6) */
    public String getIpAddress() { return ipAddress; }

    /** @return authentication method used: PASSWORD, GOOGLE, or OIDC */
    public AuthMethod getAuthMethod() { return authMethod; }

    /** @return {@code true} if this session was created with «Se souvenir de moi» */
    public boolean isRememberMe() { return rememberMe; }

    /** @return current lifecycle status: ACTIVE, EXPIRED, or REVOKED */
    public TokenStatus getStatus() { return status; }

    /** @return TTL in seconds captured from feature_flags at creation */
    public int getTtlSeconds() { return ttlSeconds; }

    /** @return timestamp of last authenticated request using this token */
    public Instant getLastUsedAt() { return lastUsedAt; }

    /** @return token expiry timestamp */
    public Instant getExpiresAt() { return expiresAt; }

    /** @return token creation timestamp */
    public Instant getCreatedAt() { return createdAt; }

    /** @return timestamp when the token was revoked, or null if not revoked */
    public Instant getRevokedAt() { return revokedAt; }

    /** @return timestamp when the token was rotated, or null if not yet rotated */
    public Instant getRotatedAt() { return rotatedAt; }

    public void setUser(final User user) { this.user = user; }
    public void setTokenHash(final String tokenHash) { this.tokenHash = tokenHash; }
    public void setDeviceFingerprint(final String deviceFingerprint) { this.deviceFingerprint = deviceFingerprint; }
    public void setDeviceName(final String deviceName) { this.deviceName = deviceName; }
    public void setUserAgent(final String userAgent) { this.userAgent = userAgent; }
    public void setIpAddress(final String ipAddress) { this.ipAddress = ipAddress; }
    public void setAuthMethod(final AuthMethod authMethod) { this.authMethod = authMethod; }
    public void setRememberMe(final boolean rememberMe) { this.rememberMe = rememberMe; }
    public void setStatus(final TokenStatus status) { this.status = status; }
    public void setTtlSeconds(final int ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    public void setLastUsedAt(final Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public void setExpiresAt(final Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setRevokedAt(final Instant revokedAt) { this.revokedAt = revokedAt; }
    public void setRotatedAt(final Instant rotatedAt) { this.rotatedAt = rotatedAt; }
}
