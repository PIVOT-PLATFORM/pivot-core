package fr.pivot.account.service;

import fr.pivot.account.dto.ExportAuditEventDto;
import fr.pivot.account.dto.ExportProfileDto;
import fr.pivot.account.dto.ExportSessionDto;
import fr.pivot.auth.entity.AuditEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExportArchiveBuilder} — the RGPD Art. 20 ZIP archive (US02.3.1).
 *
 * <p>Verifies the MVP scope from the AC: "profil, sessions, audit events" — no module data —
 * and that the audit-events entry never contains a PII string planted in an event's {@code meta}
 * (defense-in-depth check duplicating {@link fr.pivot.account.dto.ExportAuditEventDtoTest} at the
 * archive-bytes level, since that is what actually ends up in the user's downloaded file).
 */
class ExportArchiveBuilderTest {

    private final ExportArchiveBuilder builder = new ExportArchiveBuilder(JsonMapper.builder().build());

    @Test
    void build_producesZipWithManifestProfilSessionsAndAuditEvents_only() throws IOException {
        final ExportProfileDto profile = new ExportProfileDto(
            1L, "user@pivot.test", "Ada", "Lovelace", "ROLE_USER", "fr",
            true, null, "PIVOT SaaS", Instant.now(), Instant.now());
        final List<ExportSessionDto> sessions = List.of(new ExportSessionDto(
            "Chrome / macOS", "Mozilla/5.0", "203.0.113.7", "PASSWORD", false, "ACTIVE",
            Instant.now(), Instant.now(), Instant.now()));
        final List<ExportAuditEventDto> auditEvents = List.of(new ExportAuditEventDto(
            "auth.login", "203.0.113.7", "Mozilla/5.0", null, Instant.now()));

        final byte[] zip = builder.build(profile, sessions, auditEvents);

        final Map<String, byte[]> entries = readZipEntries(zip);
        assertThat(entries.keySet()).containsExactlyInAnyOrder(
            "manifest.json", "profil.json", "sessions.json", "audit-events.json");
        assertThat(new String(entries.get("profil.json"), StandardCharsets.UTF_8)).contains("user@pivot.test");
        assertThat(new String(entries.get("sessions.json"), StandardCharsets.UTF_8)).contains("Chrome / macOS");
        assertThat(new String(entries.get("audit-events.json"), StandardCharsets.UTF_8)).contains("auth.login");
    }

    @Test
    void build_neverLeaksPiiPlantedInAuditEventMeta() throws IOException {
        final ExportProfileDto profile = new ExportProfileDto(
            1L, "user@pivot.test", "Ada", "Lovelace", "ROLE_USER", "fr",
            true, null, "PIVOT SaaS", Instant.now(), Instant.now());
        // Simulates a hostile/leaky meta payload reaching the archive builder via the same
        // path production code uses (AuditEvent -> ExportAuditEventDto.from(), which sanitizes
        // internally) — this test proves the archive bytes themselves are clean end-to-end.
        final AuditEvent leakyEvent = AuditEvent.of(null, null, "module.activated", "203.0.113.7", "Mozilla/5.0",
            "{\"performedBy\":\"admin-leak@pivot.internal\"}");
        final List<ExportAuditEventDto> auditEvents = List.of(ExportAuditEventDto.from(leakyEvent));

        final byte[] zip = builder.build(profile, List.of(), auditEvents);

        final Map<String, byte[]> entries = readZipEntries(zip);
        final String auditJson = new String(entries.get("audit-events.json"), StandardCharsets.UTF_8);
        assertThat(auditJson).doesNotContain("admin-leak@pivot.internal");
    }

    private static Map<String, byte[]> readZipEntries(final byte[] zip) throws IOException {
        final Map<String, byte[]> result = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                result.put(entry.getName(), zis.readAllBytes());
            }
        }
        return result;
    }
}
