package fr.pivot.core.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link AuthenticatedPrincipal} record.
 *
 * <p>Traceability EN17.1 (volet auth, {@code pivot-core#171}, ADR-022) — confirms that the record
 * exported by {@code fr.pivot:pivot-core-starter} exposes its accessors and its structural {@code
 * equals}/{@code hashCode} contract, mirroring {@code TenantContextTest}.
 */
class AuthenticatedPrincipalTest {

    @Test
    void accessors_shouldExposeConstructedValues() {
        final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(42L, 7L, "ROLE_ADMIN");

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.tenantId()).isEqualTo(7L);
        assertThat(principal.role()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void equalsAndHashCode_shouldBeStructural() {
        final AuthenticatedPrincipal a = new AuthenticatedPrincipal(1L, 10L, "ROLE_USER");
        final AuthenticatedPrincipal b = new AuthenticatedPrincipal(1L, 10L, "ROLE_USER");
        final AuthenticatedPrincipal different = new AuthenticatedPrincipal(2L, 10L, "ROLE_USER");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
    }
}
