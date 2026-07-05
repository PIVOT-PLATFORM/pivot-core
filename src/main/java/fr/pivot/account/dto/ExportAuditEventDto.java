package fr.pivot.account.dto;

import fr.pivot.auth.entity.AuditEvent;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * "audit events" section of the RGPD Art. 20 data export (US02.3.1).
 *
 * <p>{@code audit_events.user_id} always records the <em>actor</em> of an event, so scoping
 * the export query to {@code WHERE user_id = <owner>} already guarantees only the owner's own
 * actions are ever returned — no other user's row can appear.
 *
 * <p>As defense-in-depth against the AC's explicit requirement ("les audit events inclus ne
 * contiennent pas de données personnelles d'autres utilisateurs — email admin → rôle ou ID
 * anonymisé"), {@link #sanitizeMeta(String)} strips any email address found in the free-form
 * {@code meta} JSON before it is included in the archive. This protects against a future event
 * type that records a third party's email (e.g. an admin who acted on this account) inside the
 * owner's own audit row metadata.
 */
public record ExportAuditEventDto(
    String eventType,
    String ipAddress,
    String userAgent,
    String meta,
    Instant createdAt
) {

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final String REDACTED = "[redacted-email]";

    /**
     * Builds the DTO from an {@link AuditEvent}, sanitizing {@code meta} of any embedded email.
     *
     * @param event an audit event whose {@code user} is the export subject
     * @return the corresponding audit entry, safe for inclusion in the export
     */
    public static ExportAuditEventDto from(final AuditEvent event) {
        return new ExportAuditEventDto(
            event.getEventType(),
            event.getIpAddress(),
            event.getUserAgent(),
            sanitizeMeta(event.getMeta()),
            event.getCreatedAt());
    }

    /**
     * Replaces every email address in {@code meta} with a fixed redaction marker.
     *
     * @param meta the raw {@code meta} JSON string, or {@code null}
     * @return the sanitized string, or {@code null} if {@code meta} was {@code null}
     */
    static String sanitizeMeta(final String meta) {
        if (meta == null) {
            return null;
        }
        return EMAIL_PATTERN.matcher(meta).replaceAll(REDACTED);
    }
}
