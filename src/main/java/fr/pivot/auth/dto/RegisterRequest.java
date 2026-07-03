package fr.pivot.auth.dto;

import fr.pivot.auth.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration payload (US01.2.4: password must satisfy the configured
 * {@code security.password.*} robustness policy — validated by {@link StrongPassword}).
 *
 * @param email     account email address
 * @param password  raw password — policy-checked, max 128 chars
 * @param firstName optional first name
 * @param lastName  optional last name
 * @param locale    optional UI locale ({@code fr} or {@code en})
 */
public record RegisterRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(max = 128) @StrongPassword String password,
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    @Pattern(regexp = "^(fr|en)$") String locale
) {}
