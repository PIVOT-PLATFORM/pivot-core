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

    public void setSlug(String slug) { this.slug = slug; }
    public void setName(String name) { this.name = name; }
    public void setPlan(String plan) { this.plan = plan; }
    public void setAuthMode(String authMode) { this.authMode = authMode; }
    public void setActive(boolean active) { this.active = active; }
}
