package fr.pivot.auth.entity;

import fr.pivot.tenant.entity.Tenant;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link User} accessors and defaults. */
class UserTest {

    @Test
    void defaults_areSane() {
        final User u = new User();
        assertThat(u.getRole()).isEqualTo("ROLE_USER");
        assertThat(u.isActive()).isTrue();
        assertThat(u.isBlocked()).isFalse();
        assertThat(u.isEmailVerified()).isFalse();
        assertThat(u.getCreatedAt()).isNotNull();
        assertThat(u.getUpdatedAt()).isNotNull();
    }

    @Test
    void settersAndGetters_roundTrip() {
        final User u = new User();
        final Tenant t = new Tenant();
        final Instant now = Instant.now();

        u.setTenant(t);
        u.setEmail("user@x.com");
        u.setPasswordHash("hash");
        u.setFirstName("Alice");
        u.setLastName("Doe");
        u.setRole("ROLE_ADMIN");
        u.setEmailVerified(true);
        u.setGoogleId("g-1");
        u.setOidcSubject("sub-1");
        u.setActive(false);
        u.setBlocked(true);
        u.setLastLoginAt(now);
        u.setDeletedAt(now);
        u.setScheduledDeletionAt(now);
        u.setInactivityWarningSentAt(now);

        assertThat(u.getTenant()).isSameAs(t);
        assertThat(u.getEmail()).isEqualTo("user@x.com");
        assertThat(u.getPasswordHash()).isEqualTo("hash");
        assertThat(u.getFirstName()).isEqualTo("Alice");
        assertThat(u.getLastName()).isEqualTo("Doe");
        assertThat(u.getRole()).isEqualTo("ROLE_ADMIN");
        assertThat(u.isEmailVerified()).isTrue();
        assertThat(u.getGoogleId()).isEqualTo("g-1");
        assertThat(u.getOidcSubject()).isEqualTo("sub-1");
        assertThat(u.isActive()).isFalse();
        assertThat(u.isBlocked()).isTrue();
        assertThat(u.getLastLoginAt()).isEqualTo(now);
        assertThat(u.getDeletedAt()).isEqualTo(now);
        assertThat(u.getScheduledDeletionAt()).isEqualTo(now);
    }
}
