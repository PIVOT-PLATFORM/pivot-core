package fr.pivot.auth.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CryptoUtils}.
 * Validates sha256() correctness and generateSecureToken() properties.
 */
class CryptoUtilsTest {

    // ----------------------------------------------------------------
    // sha256()
    // ----------------------------------------------------------------

    @Test
    void sha256_knownInput_producesExpectedHex() {
        // SHA-256("hello") = verified against external reference
        final String result = CryptoUtils.sha256("hello");
        assertThat(result).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void sha256_emptyString_producesKnownHash() {
        // SHA-256("") = known constant
        final String result = CryptoUtils.sha256("");
        assertThat(result).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sha256_sameInput_alwaysProducesSameHash() {
        final String input = "pivot-test-token-12345";
        assertThat(CryptoUtils.sha256(input)).isEqualTo(CryptoUtils.sha256(input));
    }

    @Test
    void sha256_differentInputs_produceDifferentHashes() {
        assertThat(CryptoUtils.sha256("token-A")).isNotEqualTo(CryptoUtils.sha256("token-B"));
    }

    @Test
    void sha256_producesLowercase64CharHex() {
        final String hash = CryptoUtils.sha256("any-input");
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "hello world", "unicode: éàü", "very-long-input-for-sha256-hashing-test-case"})
    void sha256_variousInputs_alwaysReturn64CharHex(final String input) {
        final String hash = CryptoUtils.sha256(input);
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    // ----------------------------------------------------------------
    // generateSecureToken()
    // ----------------------------------------------------------------

    @Test
    void generateSecureToken_producesUrlSafeBase64() {
        final String token = CryptoUtils.generateSecureToken();
        assertThat(token).doesNotContain("+", "/", "=");
    }

    @Test
    void generateSecureToken_isUnique_acrossMultipleCalls() {
        final String t1 = CryptoUtils.generateSecureToken();
        final String t2 = CryptoUtils.generateSecureToken();
        final String t3 = CryptoUtils.generateSecureToken();
        assertThat(t1).isNotEqualTo(t2).isNotEqualTo(t3);
        assertThat(t2).isNotEqualTo(t3);
    }

    @Test
    void generateSecureToken_hasAdequateLength() {
        // 256 bits → 32 bytes → 44 base64 chars (approx), URL-safe = no padding
        final String token = CryptoUtils.generateSecureToken();
        assertThat(token.length()).isGreaterThanOrEqualTo(40);
    }
}
