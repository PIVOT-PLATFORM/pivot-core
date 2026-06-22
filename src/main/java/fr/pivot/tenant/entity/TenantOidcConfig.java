package fr.pivot.tenant.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "tenant_oidc_configs")
public class TenantOidcConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, unique = true)
    private Tenant tenant;

    @Column(name = "issuer_uri", nullable = false, length = 500)
    private String issuerUri;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "client_secret_enc", columnDefinition = "TEXT")
    private String clientSecretEnc;

    @Column(nullable = false, length = 500)
    private String scopes = "openid email profile";

    @Column(name = "jwks_uri", length = 500)
    private String jwksUri;

    @Column(name = "auto_provision_users", nullable = false)
    private boolean autoProvisionUsers = true;

    /** Azure AD only: expected {@code tid} claim. {@code null} for non-Azure IdPs (no check). */
    @Column(name = "azure_tenant_id", length = 36)
    private String azureTenantId;

    /** {@code false} disables this IdP without deleting it (maintenance, migration). */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Tenant getTenant() { return tenant; }
    public String getIssuerUri() { return issuerUri; }
    public String getClientId() { return clientId; }
    public String getClientSecretEnc() { return clientSecretEnc; }
    public String getScopes() { return scopes; }
    public String getJwksUri() { return jwksUri; }
    public boolean isAutoProvisionUsers() { return autoProvisionUsers; }
    public String getAzureTenantId() { return azureTenantId; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }

    public void setTenant(Tenant tenant) { this.tenant = tenant; }
    public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public void setClientSecretEnc(String clientSecretEnc) { this.clientSecretEnc = clientSecretEnc; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public void setJwksUri(String jwksUri) { this.jwksUri = jwksUri; }
    public void setAutoProvisionUsers(boolean autoProvisionUsers) { this.autoProvisionUsers = autoProvisionUsers; }
    public void setAzureTenantId(String azureTenantId) { this.azureTenantId = azureTenantId; }
    public void setActive(boolean active) { this.active = active; }
}
