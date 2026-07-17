package fr.pivot.collaboratif.testsupport.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Read-only mirror of {@code public.tenants} — <strong>test-only</strong> (EN53.2 Vague 2).
 *
 * <p>See {@link PlatformUser}'s class Javadoc for why this moved from {@code src/main/java} to
 * {@code src/test/java}. Only {@code tenant_invalidation_timestamp} is mapped — the bulk
 * tenant-deactivation revocation check duplicated from {@code
 * fr.pivot.auth.service.TokenService#isTenantInvalidated}, needed here only to keep {@link
 * fr.pivot.collaboratif.testsupport.auth.TestAuthenticatedPrincipalResolver}'s behavior identical
 * to the shell's own production resolver, for this module's isolated test suite.
 */
@Entity
@Table(schema = "public", name = "tenants")
public class PlatformTenant {

    @Id
    private Long id;

    @Column(name = "tenant_invalidation_timestamp")
    private Instant tenantInvalidationTimestamp;

    /** No-argument constructor required by JPA. */
    protected PlatformTenant() {
    }

    /** @return database primary key ({@code public.tenants.id}) */
    public Long getId() {
        return id;
    }

    /**
     * @return the timestamp of this tenant's last deactivation, or {@code null} if the tenant
     *     was never deactivated
     */
    public Instant getTenantInvalidationTimestamp() {
        return tenantInvalidationTimestamp;
    }
}
