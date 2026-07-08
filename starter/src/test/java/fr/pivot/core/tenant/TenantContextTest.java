package fr.pivot.core.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour le record {@link TenantContext}.
 *
 * <p>Traçabilité EN17.1 (volet tenant, pivot-core#171) — confirme que le record exporté par
 * {@code fr.pivot:pivot-core-starter} expose ses accesseurs et son contrat {@code equals}/
 * {@code hashCode} (identité structurelle, requis pour les comparaisons en test des repos
 * modules consommateurs).
 */
class TenantContextTest {

    @Test
    void accessors_shouldExposeConstructedValues() {
        final TenantContext context = new TenantContext(42L, "user-1", "ROLE_ADMIN");

        assertThat(context.tenantId()).isEqualTo(42L);
        assertThat(context.userId()).isEqualTo("user-1");
        assertThat(context.role()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void equalsAndHashCode_shouldBeStructural() {
        final TenantContext a = new TenantContext(1L, "user-1", "ROLE_USER");
        final TenantContext b = new TenantContext(1L, "user-1", "ROLE_USER");
        final TenantContext different = new TenantContext(2L, "user-1", "ROLE_USER");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
    }
}
