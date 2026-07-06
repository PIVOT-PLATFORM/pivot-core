package fr.pivot.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for POST /auth/suspicious-login/confirm — the "Not me" link (US01.4.3a).
 *
 * <p>Requires the account's CURRENT password: a full re-authentication. Clicking the emailed
 * link alone (the {@code token} query param the frontend forwards here) must never be enough to
 * revoke sessions or untrust a device — e.g. if the mailbox itself is what was compromised, the
 * attacker would have the link but not the password.
 *
 * @param token           raw single-use "Not me" token from the email link (TTL 1h)
 * @param currentPassword the account's current password, verified before any action is taken
 */
public record SuspiciousLoginConfirmRequest(
    @NotBlank String token,
    @NotBlank String currentPassword
) {}
