package fr.pivot.tenant.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link TenantOidcConfig} accessors and defaults. */
class TenantOidcConfigTest {

    @Test
    void defaults_areSane() {
        final TenantOidcConfig c = new TenantOidcConfig();
        assertThat(c.getScopes()).isEqualTo("openid email profile");
        assertThat(c.isAutoProvisionUsers()).isTrue();
        assertThat(c.getCreatedAt()).isNotNull();
        assertThat(c.getId()).isNull();
    }

    @Test
    void settersAndGetters_roundTrip() {
        final TenantOidcConfig c = new TenantOidcConfig();
        final Tenant t = new Tenant();

        c.setTenant(t);
        c.setIssuerUri("https://idp");
        c.setClientId("client-1");
        c.setClientSecretEnc("enc");
        c.setScopes("openid");
        c.setJwksUri("https://idp/jwks");
        c.setAutoProvisionUsers(false);

        assertThat(c.getTenant()).isSameAs(t);
        assertThat(c.getIssuerUri()).isEqualTo("https://idp");
        assertThat(c.getClientId()).isEqualTo("client-1");
        assertThat(c.getClientSecretEnc()).isEqualTo("enc");
        assertThat(c.getScopes()).isEqualTo("openid");
        assertThat(c.getJwksUri()).isEqualTo("https://idp/jwks");
        assertThat(c.isAutoProvisionUsers()).isFalse();
    }
}
