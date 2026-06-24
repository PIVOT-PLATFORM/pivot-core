package fr.pivot.auth.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link AuthMethod} — persists as lowercase string.
 */
@Converter(autoApply = true)
public class AuthMethodConverter implements AttributeConverter<AuthMethod, String> {

    @Override
    public String convertToDatabaseColumn(final AuthMethod attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.name().toLowerCase();
    }

    @Override
    public AuthMethod convertToEntityAttribute(final String dbData) {
        if (dbData == null) {
            return null;
        }
        return AuthMethod.valueOf(dbData.toUpperCase());
    }
}
