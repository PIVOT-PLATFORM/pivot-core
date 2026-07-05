package fr.pivot.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Change-email payload for the authenticated "my account" flow (US02.2.2).
 *
 * <p>Deliberately carries no {@code userId} / {@code accountId} field — identity is resolved
 * exclusively from the bearer token by {@code AccountEmailController}, never from the body.
 *
 * @param newEmail        candidate new email address — a confirmation link is sent here
 * @param currentPassword current raw password — verified against the stored BCrypt hash before
 *                        any confirmation link is issued
 */
public record ChangeEmailRequest(
    @NotBlank @Email String newEmail,
    @NotBlank String currentPassword
) {}
