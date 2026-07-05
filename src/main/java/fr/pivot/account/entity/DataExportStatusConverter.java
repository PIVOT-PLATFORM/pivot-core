package fr.pivot.account.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link DataExportStatus} — persists as lowercase string.
 * Bridges Java enum convention (PENDING) with DB convention (pending).
 */
@Converter(autoApply = true)
public class DataExportStatusConverter implements AttributeConverter<DataExportStatus, String> {

    @Override
    public String convertToDatabaseColumn(final DataExportStatus attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.name().toLowerCase();
    }

    @Override
    public DataExportStatus convertToEntityAttribute(final String dbData) {
        if (dbData == null) {
            return null;
        }
        return DataExportStatus.valueOf(dbData.toUpperCase());
    }
}
