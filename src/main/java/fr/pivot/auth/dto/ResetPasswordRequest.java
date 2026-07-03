package fr.pivot.auth.dto;

import fr.pivot.auth.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Reset-password payload (US01.2.4: the new password must satisfy the same
 * {@code security.password.*} robustness policy as registration).
 *
 * @param token       raw reset token from the email link
 * @param newPassword new raw password — policy-checked, max 128 chars
 */
public record ResetPasswordRequest(
    @NotBlank String token,
    @NotBlank @Size(max = 128) @StrongPassword String newPassword
) {}
