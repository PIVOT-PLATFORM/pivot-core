package fr.pivot.auth.dto;

import fr.pivot.auth.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Change-password payload for the authenticated "my account" flow (US02.2.1).
 *
 * <p>Deliberately carries no {@code userId} / {@code accountId} field — identity is resolved
 * exclusively from the bearer token by {@link fr.pivot.auth.controller.AccountController}. Any
 * unexpected field in the JSON body (e.g. a spoofed {@code userId}) is rejected with 400 —
 * enforced globally via {@code spring.jackson.deserialization.fail-on-unknown-properties: true}
 * in {@code application.yml} (Jackson 3.x flips this default to permissive; a per-DTO
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} was tried first and does NOT reliably
 * override the mapper-level default in this Jackson version — only the global setting does).
 * See {@code AccountControllerIntegrationTest} for the regression test.
 *
 * @param currentPassword current raw password — verified against the stored BCrypt hash
 * @param newPassword     new raw password — policy-checked (US01.2.4), max 128 chars
 */
public record ChangePasswordRequest(
    @NotBlank String currentPassword,
    @NotBlank @Size(max = 128) @StrongPassword String newPassword
) {}
