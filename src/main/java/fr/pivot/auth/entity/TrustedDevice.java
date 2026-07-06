package fr.pivot.auth.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "trusted_devices")
public class TrustedDevice {

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

    /**
     * IP address the device was trusted from — captured once at {@code trust()} time
     * (either the first confirmation or a later re-confirmation), never updated on plain
     * {@code isTrusted()} lookups. Mirrors {@code SessionDto.ip} semantics: "the IP the
     * session/trust was created from", not a live-updated value.
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getDeviceFingerprint() { return deviceFingerprint; }
    public String getDeviceName() { return deviceName; }
    public String getIpAddress() { return ipAddress; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public boolean isExpired() { return expiresAt.isBefore(Instant.now()); }

    public void setUser(User user) { this.user = user; }
    public void setDeviceFingerprint(String deviceFingerprint) { this.deviceFingerprint = deviceFingerprint; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
