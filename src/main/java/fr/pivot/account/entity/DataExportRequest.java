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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Tracks a personal-data export request (RGPD Art. 20 — US02.3.1).
 *
 * <p>One row per {@code POST /api/account/export} call. The generated archive is stored on
 * the local filesystem ({@link fr.pivot.account.service.ExportStorageService}) — only the
 * SHA-256 hash of the one-time download token is persisted here. The raw token is emailed to
 * the user and never stored, mirroring {@code access_tokens} / {@code password_reset_tokens}.
 */
@Entity
@Table(name = "data_export_requests")
public class DataExportRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 10)
    private DataExportStatus status = DataExportStatus.PENDING;

    /** SHA-256 hex-encoded hash of the raw download token — {@code null} until {@code READY}. */
    @Column(name = "token_hash", unique = true, length = 64)
    private String tokenHash;

    /** Absolute path of the generated ZIP archive on local storage. */
    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    /** Set only when {@code status = FAILED}. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Download link TTL (24h after {@link #completedAt} by default). */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @PrePersist
    private void onCreate() {
        if (requestedAt == null) {
            requestedAt = Instant.now();
        }
    }

    // ----------------------------------------------------------------
    // Domain logic
    // ----------------------------------------------------------------

    /** @return {@code true} if this request is still being generated (blocks a new request). */
    public boolean isInProgress() {
        return status == DataExportStatus.PENDING || status == DataExportStatus.PROCESSING;
    }

    /** @return {@code true} if the download link TTL has elapsed. */
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    // ----------------------------------------------------------------
    // Accessors
    // ----------------------------------------------------------------

    public Long getId() { return id; }
    public User getUser() { return user; }
    public DataExportStatus getStatus() { return status; }
    public String getTokenHash() { return tokenHash; }
    public String getFilePath() { return filePath; }
    public Long getFileSizeBytes() { return fileSizeBytes; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public void setUser(final User user) { this.user = user; }
    public void setStatus(final DataExportStatus status) { this.status = status; }
    public void setTokenHash(final String tokenHash) { this.tokenHash = tokenHash; }
    public void setFilePath(final String filePath) { this.filePath = filePath; }
    public void setFileSizeBytes(final Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }
    public void setErrorMessage(final String errorMessage) { this.errorMessage = errorMessage; }
    public void setCompletedAt(final Instant completedAt) { this.completedAt = completedAt; }
    public void setExpiresAt(final Instant expiresAt) { this.expiresAt = expiresAt; }
}
