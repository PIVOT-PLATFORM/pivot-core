package fr.pivot.auth.entity;

import fr.pivot.tenant.entity.Tenant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link AuditEvent} factory and accessors. */
class AuditEventTest {

    @Test
    void of_populatesAllFields() {
        final User user = new User();
        final Tenant tenant = new Tenant();

        final AuditEvent e = AuditEvent.of(user, tenant, "auth.login", "1.2.3.4", "ua", "{\"k\":1}");

        assertThat(e.getUser()).isSameAs(user);
        assertThat(e.getTenant()).isSameAs(tenant);
        assertThat(e.getEventType()).isEqualTo("auth.login");
        assertThat(e.getIpAddress()).isEqualTo("1.2.3.4");
        assertThat(e.getUserAgent()).isEqualTo("ua");
        assertThat(e.getMeta()).isEqualTo("{\"k\":1}");
        assertThat(e.getCreatedAt()).isNotNull();
    }

    @Test
    void of_nullIp_defaultsToEmptyString() {
        final AuditEvent e = AuditEvent.of(null, null, "auth.logout", null, null, null);

        assertThat(e.getIpAddress()).isEmpty();
        assertThat(e.getUser()).isNull();
        assertThat(e.getTenant()).isNull();
        assertThat(e.getId()).isNull();
    }
}
