package fr.pivot.account.dto;

import java.time.Instant;

/**
 * Response of a successful {@code DELETE /account} (US02.2.4) — the account is now
 * {@code PENDING_DELETION}; every session was revoked immediately.
 *
 * @param effectiveDeletionDate the instant the grace period elapses and anonymization runs,
 *                              identical to the date stated in the confirmation email
 */
public record AccountDeletionResponseDto(Instant effectiveDeletionDate) {
}
