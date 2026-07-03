package fr.pivot.auth.dto;

import fr.pivot.auth.validation.PasswordPolicyProperties;

/**
 * Public representation of the password robustness policy (US01.2.4).
 *
 * <p>Returned by {@code GET /auth/password-policy} so the Angular frontend displays
 * the exact same criteria the backend enforces — no duplicated hardcoded values.
 *
 * @param minLength    minimum password length
 * @param minUppercase minimum number of uppercase letters
 * @param minDigits    minimum number of digits
 * @param minSpecial   minimum number of special characters
 */
public record PasswordPolicyDto(int minLength, int minUppercase, int minDigits, int minSpecial) {

    /**
     * Maps the bound configuration properties to their public DTO.
     *
     * @param props the configured password policy
     * @return the DTO exposed on the public API
     */
    public static PasswordPolicyDto from(final PasswordPolicyProperties props) {
        return new PasswordPolicyDto(
            props.minLength(), props.minUppercase(), props.minDigits(), props.minSpecial());
    }
}
