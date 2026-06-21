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
 * Runtime-configurable feature flag with typed value support (bool / int / float).
 *
 * <p>Boolean flags use {@code enabled} for backward compatibility.
 * Typed flags (int, float) use {@code value} for serialized string representation.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code MFA_NEW_DEVICE_OTP}: type=bool, value="false", enabled=false</li>
 *   <li>{@code SESSION_TTL_SECONDS}: type=int, value="86400"</li>
 *   <li>{@code SESSION_REFRESH_THRESHOLD}: type=float, value="0.5"}</li>
 * </ul>
 */
@Entity
@Table(name = "feature_flags")
public class FeatureFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique flag identifier — used as lookup key (e.g. "SESSION_TTL_SECONDS"). */
    @Column(name = "flag_key", nullable = false, unique = true, length = 100)
    private String flagKey;

    /** Fast boolean accessor for bool-type flags. Set from {@code value} on migration. */
    @Column(nullable = false)
    private boolean enabled = false;

    /**
     * Serialized typed value.
     * <ul>
     *   <li>bool: {@code "true"} or {@code "false"}</li>
     *   <li>int: numeric string, e.g. {@code "86400"}</li>
     *   <li>float: decimal string, e.g. {@code "0.5"}</li>
     * </ul>
     */
    @Column(name = "value", nullable = false, length = 255)
    private String value;

    /** Value type: {@code bool}, {@code int}, or {@code float}. */
    @Column(name = "type", nullable = false, length = 10)
    private String type = "bool";

    /** Human-readable label for the admin interface. */
    @Column(name = "label", length = 128)
    private String label;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    // ----------------------------------------------------------------
    // Accessors
    // ----------------------------------------------------------------

    /** @return database primary key */
    public Long getId() { return id; }

    /** @return unique flag key */
    public String getFlagKey() { return flagKey; }

    /** @return {@code true} for enabled boolean flags */
    public boolean isEnabled() { return enabled; }

    /** @return serialized typed value string */
    public String getValue() { return value; }

    /** @return value type: bool, int, or float */
    public String getType() { return type; }

    /** @return admin-facing label */
    public String getLabel() { return label; }

    /** @return description visible in admin UI */
    public String getDescription() { return description; }

    /** @return timestamp of last modification */
    public Instant getUpdatedAt() { return updatedAt; }

    /** @return admin user who last modified this flag */
    public User getUpdatedBy() { return updatedBy; }

    /**
     * Updates the boolean state and synchronizes the {@code value} field.
     *
     * @param enabled new boolean state
     */
    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
        this.value = String.valueOf(enabled);
        this.updatedAt = Instant.now();
    }

    /**
     * Sets the typed value string and updates {@code enabled} for bool-type flags.
     *
     * @param value serialized new value
     */
    public void setValue(final String value) {
        this.value = value;
        if ("bool".equals(type)) {
            this.enabled = Boolean.parseBoolean(value);
        }
        this.updatedAt = Instant.now();
    }

    public void setUpdatedBy(final User updatedBy) { this.updatedBy = updatedBy; }
}
