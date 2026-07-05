package fr.pivot.account.service;

import tools.jackson.databind.ObjectMapper;
import fr.pivot.account.dto.ExportAuditEventDto;
import fr.pivot.account.dto.ExportProfileDto;
import fr.pivot.account.dto.ExportSessionDto;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the ZIP archive delivered by the RGPD Art. 20 personal-data export flow (US02.3.1).
 *
 * <p>MVP scope (per AC): {@code profil.json}, {@code sessions.json} and
 * {@code audit-events.json}. Collaborative-module data is deferred to a later phase.
 * Reuses the Spring-managed {@link ObjectMapper} bean rather than instantiating a new one.
 */
@Component
public class ExportArchiveBuilder {

    private static final String FORMAT_VERSION = "pivot-data-export-v1";

    private final ObjectMapper objectMapper;

    public ExportArchiveBuilder(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Serializes the export sections into a single ZIP archive in memory.
     *
     * @param profile     the "profil" section
     * @param sessions    the "sessions" section (owner's own sessions only)
     * @param auditEvents the "audit events" section (owner-scoped and PII-sanitized)
     * @return the ZIP archive bytes
     * @throws IOException if JSON serialization or ZIP writing fails
     */
    public byte[] build(final ExportProfileDto profile,
                         final List<ExportSessionDto> sessions,
                         final List<ExportAuditEventDto> auditEvents) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            writeEntry(zip, "manifest.json", Map.of(
                "generatedAt", Instant.now().toString(),
                "format", FORMAT_VERSION));
            writeEntry(zip, "profil.json", profile);
            writeEntry(zip, "sessions.json", sessions);
            writeEntry(zip, "audit-events.json", auditEvents);
        }
        return baos.toByteArray();
    }

    private void writeEntry(final ZipOutputStream zip, final String name, final Object content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(content));
        zip.closeEntry();
    }
}
