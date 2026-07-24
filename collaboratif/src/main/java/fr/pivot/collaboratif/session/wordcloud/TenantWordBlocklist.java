package fr.pivot.collaboratif.session.wordcloud;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A tenant-level, reusable blocklisted word — applies across every WORDCLOUD activity for that
 * tenant (US19.3.3).
 */
@Entity
@Table(name = "tenant_word_blocklist", schema = "collaboratif")
public class TenantWordBlocklist {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(nullable = false, updatable = false, length = 30)
    private String word;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TenantWordBlocklist() {
    }

    /**
     * Creates a new blocklist entry.
     *
     * @param tenantId  the owning tenant
     * @param word      the normalized blocked word
     * @param createdAt creation timestamp
     */
    public TenantWordBlocklist(final Long tenantId, final String word, final Instant createdAt) {
        this.tenantId = tenantId;
        this.word = word;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getWord() {
        return word;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
