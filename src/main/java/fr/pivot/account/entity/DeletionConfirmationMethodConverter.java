package fr.pivot.account.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link DeletionConfirmationMethod} — persists as lowercase string.
 * Bridges Java enum convention (PASSWORD) with DB convention (password).
 */
@Converter(autoApply = true)
public class DeletionConfirmationMethodConverter
        implements AttributeConverter<DeletionConfirmationMethod, String> {

    @Override
    public String convertToDatabaseColumn(final DeletionConfirmationMethod attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.name().toLowerCase();
    }

    @Override
    public DeletionConfirmationMethod convertToEntityAttribute(final String dbData) {
        if (dbData == null) {
            return null;
        }
        return DeletionConfirmationMethod.valueOf(dbData.toUpperCase());
    }
}
