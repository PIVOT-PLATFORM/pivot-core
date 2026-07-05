package fr.pivot.tenant.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link Tenant} accessors and defaults. */
class TenantTest {

    @Test
    void defaults_areSane() {
        final Tenant t = new Tenant();
        assertThat(t.getPlan()).isEqualTo("SAAS");
        assertThat(t.getAuthMode()).isEqualTo("SAAS");
        assertThat(t.isActive()).isTrue();
        assertThat(t.getCreatedAt()).isNotNull();
        assertThat(t.getUpdatedAt()).isNotNull();
        // Never deactivated by default — must never block a legitimate token (US06.2.2).
        assertThat(t.getTenantInvalidationTimestamp()).isNull();
    }

    @Test
    void settersAndGetters_roundTrip() {
        final Tenant t = new Tenant();
        t.setSlug("acme");
        t.setName("ACME Corp");
        t.setPlan("ENTERPRISE");
        t.setAuthMode("OIDC");
        t.setActive(false);

        assertThat(t.getSlug()).isEqualTo("acme");
        assertThat(t.getName()).isEqualTo("ACME Corp");
        assertThat(t.getPlan()).isEqualTo("ENTERPRISE");
        assertThat(t.getAuthMode()).isEqualTo("OIDC");
        assertThat(t.isActive()).isFalse();
        assertThat(t.getId()).isNull();
    }

    @Test
    void tenantInvalidationTimestamp_roundTrip() {
        final Tenant t = new Tenant();
        final Instant now = Instant.now();

        t.setTenantInvalidationTimestamp(now);

        assertThat(t.getTenantInvalidationTimestamp()).isEqualTo(now);
    }
}
