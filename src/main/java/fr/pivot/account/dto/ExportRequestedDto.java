package fr.pivot.account.dto;

import java.time.Instant;

/**
 * Response body for {@code POST /api/account/export} (US02.3.1) — {@code 202 Accepted}.
 *
 * @param requestId   primary key of the newly created {@code data_export_requests} row
 * @param status      always {@code "PENDING"} on creation
 * @param requestedAt creation timestamp
 */
public record ExportRequestedDto(Long requestId, String status, Instant requestedAt) {
}
