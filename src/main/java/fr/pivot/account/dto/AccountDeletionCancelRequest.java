package fr.pivot.account.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /account/deletion/cancel} (US02.2.4) — unauthenticated by design, the
 * cancellation link may be opened on a device with no active PIVOT session (every session was
 * revoked the moment the deletion was requested). Identity comes solely from the token.
 *
 * @param token raw single-use cancellation token from the {@code ?token=} query parameter of
 *              the confirmation email's cancellation link
 */
public record AccountDeletionCancelRequest(@NotBlank String token) {
}
