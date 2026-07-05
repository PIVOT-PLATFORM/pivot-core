package fr.pivot.auth.mapper;

import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.entity.User;
import fr.pivot.tenant.entity.Tenant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UserMapper} (US02.1.2 gap closure).
 *
 * <p>Prior to this test, {@code toUserInfo} was only ever exercised with hand-built
 * {@code UserInfo} fixtures hard-coded to {@code "fr"} in the auth controller tests
 * (login, Google, OIDC) — the mapper itself was never called with a non-default
 * {@code locale}. These tests close that gap by asserting the real mapping for both
 * supported locales.
 */
class UserMapperTest {

    @Test
    void toUserInfo_mapsPreferredLanguage_forDefaultLocale() {
        final User user = buildUser("fr");

        final AuthResponse.UserInfo info = UserMapper.toUserInfo(user);

        assertThat(info.preferredLanguage()).isEqualTo("fr");
    }

    @Test
    void toUserInfo_mapsPreferredLanguage_forNonDefaultLocale() {
        final User user = buildUser("en");

        final AuthResponse.UserInfo info = UserMapper.toUserInfo(user);

        assertThat(info.preferredLanguage()).isEqualTo("en");
    }

    @Test
    void toUserInfo_mapsAllFields() {
        final User user = buildUser("en");

        final AuthResponse.UserInfo info = UserMapper.toUserInfo(user);

        assertThat(info.email()).isEqualTo("alice@pivot.test");
        assertThat(info.firstName()).isEqualTo("Alice");
        assertThat(info.lastName()).isEqualTo("Martin");
        assertThat(info.role()).isEqualTo("ROLE_USER");
        assertThat(info.emailVerified()).isTrue();
        assertThat(info.tenantId()).isEqualTo(42L);
        assertThat(info.tenantSlug()).isEqualTo("acme");
        assertThat(info.preferredLanguage()).isEqualTo("en");
    }

    private static User buildUser(final String locale) {
        final Tenant tenant = new Tenant();
        setId(tenant, 42L);
        tenant.setSlug("acme");
        tenant.setName("Acme");

        final User user = new User();
        setId(user, 1L);
        user.setTenant(tenant);
        user.setEmail("alice@pivot.test");
        user.setFirstName("Alice");
        user.setLastName("Martin");
        user.setRole("ROLE_USER");
        user.setEmailVerified(true);
        user.setLocale(locale);
        return user;
    }

    /** {@code id} is {@code @GeneratedValue}, no public setter — set via reflection for tests. */
    private static void setId(final Object entity, final Long id) {
        try {
            final java.lang.reflect.Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to set id via reflection for test fixture", e);
        }
    }
}
