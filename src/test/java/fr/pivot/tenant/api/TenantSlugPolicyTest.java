package fr.pivot.tenant.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link TenantSlugPolicy} — US06.2.1 format regex and reserved-word blocklist. */
class TenantSlugPolicyTest {

    private static final String FIFTY_CHARS =
            "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghij";
    private static final String FIFTY_ONE_CHARS = FIFTY_CHARS + "a";

    @ParameterizedTest
    @ValueSource(strings = {"abc", "acme-corp", "a1-b2-c3", "-acme", FIFTY_CHARS})
    void matchesFormat_returnsTrue_forValidSlugs(final String slug) {
        // "-acme" is intentionally accepted: the AC's regex is the literal, un-anchored
        // [a-z0-9-]{3,50} — it does not forbid a leading/trailing hyphen. Not our call to
        // tighten it unilaterally (see CLAUDE.md: ambiguous AC → clarify, never reinterpret).
        assertThat(TenantSlugPolicy.matchesFormat(slug)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "AB", "Acme-Corp", "acme_corp", "acme corp", FIFTY_ONE_CHARS, ""})
    void matchesFormat_returnsFalse_forInvalidSlugs(final String slug) {
        assertThat(TenantSlugPolicy.matchesFormat(slug)).isFalse();
    }

    @Test
    void matchesFormat_returnsFalse_forNull() {
        assertThat(TenantSlugPolicy.matchesFormat(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "admin", "superadmin", "auth", "actuator", "health", "system", "pivot"})
    void isReserved_returnsTrue_forBlocklistedWords(final String slug) {
        assertThat(TenantSlugPolicy.isReserved(slug)).isTrue();
    }

    @Test
    void isReserved_isCaseInsensitive() {
        assertThat(TenantSlugPolicy.isReserved("ADMIN")).isTrue();
    }

    @Test
    void isReserved_returnsFalse_forOrdinarySlug() {
        assertThat(TenantSlugPolicy.isReserved("acme-corp")).isFalse();
    }

    @Test
    void isReserved_returnsFalse_forNull() {
        assertThat(TenantSlugPolicy.isReserved(null)).isFalse();
    }
}
