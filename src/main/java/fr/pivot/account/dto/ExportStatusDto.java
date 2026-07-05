package fr.pivot.account.dto;

import java.time.Instant;

/**
 * Response body for {@code GET /api/account/export/status} (US02.3.1).
 *
 * <p>Lets the frontend render the "Demander mon export" button's disabled/enabled state
 * (with the {@code nextAvailableAt} hint) without first attempting a {@code POST}.
 *
 * @param status          one of {@code NONE, PENDING, PROCESSING, READY, FAILED}
 * @param requestedAt     when the latest request was created, or {@code null} if {@code status = NONE}
 * @param completedAt     when the latest request finished (READY or FAILED), or {@code null}
 * @param expiresAt       download-link expiry when {@code status = READY}, or {@code null}
 * @param nextAvailableAt when a new export may be requested, or {@code null} if allowed now
 */
public record ExportStatusDto(
    String status,
    Instant requestedAt,
    Instant completedAt,
    Instant expiresAt,
    Instant nextAvailableAt
) {
}
