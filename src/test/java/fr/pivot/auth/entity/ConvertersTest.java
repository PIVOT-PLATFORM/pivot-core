package fr.pivot.auth.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the JPA {@link jakarta.persistence.AttributeConverter}s
 * ({@link TokenStatusConverter}, {@link AuthMethodConverter}) — round-trip + null handling.
 */
class ConvertersTest {

    private final TokenStatusConverter tokenStatus = new TokenStatusConverter();
    private final AuthMethodConverter authMethod = new AuthMethodConverter();

    @Test
    void tokenStatus_toDb_lowercases() {
        assertThat(tokenStatus.convertToDatabaseColumn(TokenStatus.ACTIVE)).isEqualTo("active");
        assertThat(tokenStatus.convertToDatabaseColumn(TokenStatus.REVOKED)).isEqualTo("revoked");
    }

    @Test
    void tokenStatus_fromDb_uppercases() {
        assertThat(tokenStatus.convertToEntityAttribute("active")).isEqualTo(TokenStatus.ACTIVE);
        assertThat(tokenStatus.convertToEntityAttribute("expired")).isEqualTo(TokenStatus.EXPIRED);
    }

    @Test
    void tokenStatus_handlesNull() {
        assertThat(tokenStatus.convertToDatabaseColumn(null)).isNull();
        assertThat(tokenStatus.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void authMethod_toDb_lowercases() {
        assertThat(authMethod.convertToDatabaseColumn(AuthMethod.PASSWORD)).isEqualTo("password");
        assertThat(authMethod.convertToDatabaseColumn(AuthMethod.GOOGLE)).isEqualTo("google");
    }

    @Test
    void authMethod_fromDb_uppercases() {
        assertThat(authMethod.convertToEntityAttribute("password")).isEqualTo(AuthMethod.PASSWORD);
        assertThat(authMethod.convertToEntityAttribute("oidc")).isEqualTo(AuthMethod.OIDC);
    }

    @Test
    void authMethod_handlesNull() {
        assertThat(authMethod.convertToDatabaseColumn(null)).isNull();
        assertThat(authMethod.convertToEntityAttribute(null)).isNull();
    }
}
