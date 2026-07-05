package fr.pivot.account.dto;

import fr.pivot.auth.entity.AuditEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExportAuditEventDto} — PII redaction in the RGPD Art. 20 export
 * (US02.3.1 AC: "les audit events inclus ne contiennent pas de données personnelles d'autres
 * utilisateurs — email admin → rôle ou ID anonymisé").
 */
class ExportAuditEventDtoTest {

    @Test
    void sanitizeMeta_redactsEmailAddress() {
        final String meta = "{\"actorRole\":\"ROLE_ADMIN\",\"actorEmail\":\"admin@corp.example\"}";

        final String sanitized = ExportAuditEventDto.sanitizeMeta(meta);

        assertThat(sanitized)
            .doesNotContain("admin@corp.example")
            .contains("[redacted-email]")
            .contains("ROLE_ADMIN");
    }

    @Test
    void sanitizeMeta_redactsMultipleEmails() {
        final String meta = "{\"from\":\"a@x.test\",\"to\":\"b@y.test\"}";

        final String sanitized = ExportAuditEventDto.sanitizeMeta(meta);

        assertThat(sanitized).doesNotContain("a@x.test").doesNotContain("b@y.test");
    }

    @Test
    void sanitizeMeta_returnsNull_whenMetaIsNull() {
        assertThat(ExportAuditEventDto.sanitizeMeta(null)).isNull();
    }

    @Test
    void sanitizeMeta_leavesNonEmailContentUnchanged() {
        final String meta = "{\"moduleId\":\"whiteboard\",\"action\":\"activated\"}";

        assertThat(ExportAuditEventDto.sanitizeMeta(meta)).isEqualTo(meta);
    }

    @Test
    void from_buildsDtoWithSanitizedMeta() {
        final AuditEvent event = AuditEvent.of(null, null, "module.activated", "10.0.0.1", "JUnit",
            "{\"performedBy\":\"leaked-admin@pivot.internal\"}");

        final ExportAuditEventDto dto = ExportAuditEventDto.from(event);

        assertThat(dto.eventType()).isEqualTo("module.activated");
        assertThat(dto.meta()).doesNotContain("leaked-admin@pivot.internal");
    }
}
