package fr.pivot.tenant.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 50)
    private String plan = "SAAS";

    @Column(name = "auth_mode", nullable = false, length = 20)
    private String authMode = "SAAS";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Timestamp of the tenant's last deactivation (US06.2.2 « Super admin désactive un
     * tenant »). {@code null} means the tenant was never deactivated.
     *
     * <p>Bulk session revocation strategy: rather than iterating over every user's
     * {@code AccessToken} rows to revoke them individually (O(n) users), a single row
     * update on this column invalidates every token of every user of this tenant in O(1).
     * {@code TokenService#validate} rejects any token whose {@code created_at} is not
     * strictly after this timestamp — see that method for the enforcement logic.
     */
    @Column(name = "tenant_invalidation_timestamp")
    private Instant tenantInvalidationTimestamp;

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getPlan() { return plan; }
    public String getAuthMode() { return authMode; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** @return the timestamp of the tenant's last deactivation, or {@code null} if never deactivated */
    public Instant getTenantInvalidationTimestamp() { return tenantInvalidationTimestamp; }

    public void setSlug(String slug) { this.slug = slug; }
    public void setName(String name) { this.name = name; }
    public void setPlan(String plan) { this.plan = plan; }
    public void setAuthMode(String authMode) { this.authMode = authMode; }
    public void setActive(boolean active) { this.active = active; }

    /**
     * Sets the tenant invalidation timestamp — bulk-revokes every token issued at or
     * before this instant for every user of this tenant (see {@link #tenantInvalidationTimestamp}).
     *
     * @param tenantInvalidationTimestamp the new invalidation timestamp, or {@code null} to clear it
     */
    public void setTenantInvalidationTimestamp(Instant tenantInvalidationTimestamp) {
        this.tenantInvalidationTimestamp = tenantInvalidationTimestamp;
    }
}
