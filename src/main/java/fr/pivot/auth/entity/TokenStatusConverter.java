package fr.pivot.auth.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link TokenStatus} — persists as lowercase string.
 * Bridges Java enum convention (ACTIVE) with DB convention (active).
 */
@Converter(autoApply = true)
public class TokenStatusConverter implements AttributeConverter<TokenStatus, String> {

    @Override
    public String convertToDatabaseColumn(final TokenStatus attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.name().toLowerCase();
    }

    @Override
    public TokenStatus convertToEntityAttribute(final String dbData) {
        if (dbData == null) {
            return null;
        }
        return TokenStatus.valueOf(dbData.toUpperCase());
    }
}
