package fr.pivot.collaboratif.session.wordcloud;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the {@link TenantWordBlocklist} entity (US19.3.3) — the entity carries no
 * business logic beyond wiring its constructor arguments to accessors, but had zero test
 * coverage: {@link WordcloudActivityServiceTest} only exercises the repository's {@code
 * existsByTenantIdAndWord} boolean query through a mock, never the entity itself, so a bug in the
 * field wiring here (e.g. a swapped constructor argument) would not be caught anywhere.
 */
class TenantWordBlocklistTest {

    @Test
    void constructorWiresAllFieldsToTheirAccessors() {
        Instant createdAt = Instant.parse("2026-07-01T10:15:30Z");

        TenantWordBlocklist entry = new TenantWordBlocklist(42L, "spam", createdAt);

        assertThat(entry.getTenantId()).isEqualTo(42L);
        assertThat(entry.getWord()).isEqualTo("spam");
        assertThat(entry.getCreatedAt()).isEqualTo(createdAt);
        // Id is database-generated (GenerationType.UUID) and only populated on persist.
        assertThat(entry.getId()).isNull();
    }

    @Test
    void twoEntriesForDifferentTenantsWithTheSameWordAreIndependentInstances() {
        Instant now = Instant.now();

        TenantWordBlocklist tenantA = new TenantWordBlocklist(1L, "badword", now);
        TenantWordBlocklist tenantB = new TenantWordBlocklist(2L, "badword", now);

        assertThat(tenantA.getTenantId()).isNotEqualTo(tenantB.getTenantId());
        assertThat(tenantA.getWord()).isEqualTo(tenantB.getWord());
    }
}
