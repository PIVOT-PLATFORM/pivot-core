package fr.pivot.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates a raw password against the configured {@link PasswordPolicyProperties} (US01.2.4).
 *
 * <p>Character classification is Unicode-aware:
 * <ul>
 *   <li>uppercase — {@link Character#isUpperCase(int)} (e.g. {@code É} counts)</li>
 *   <li>digit — {@link Character#isDigit(int)} (e.g. Arabic-Indic digits count)</li>
 *   <li>special — any code point that is neither a letter nor a digit
 *       ({@code !Character.isLetterOrDigit}), including emoji and punctuation</li>
 * </ul>
 *
 * <p>Length is measured in UTF-16 units ({@link String#length()}) to stay consistent
 * with the JavaScript {@code String.length} used by the Angular frontend.
 *
 * <p>Instantiated by Spring's {@code SpringConstraintValidatorFactory}, which injects
 * the policy properties. {@code null} passwords are valid — {@code @NotBlank} owns that rule.
 */
public class PasswordValidator implements ConstraintValidator<StrongPassword, String> {

    private final PasswordPolicyProperties policy;

    /**
     * Constructs the validator with the configured policy.
     *
     * @param policy password policy bound from {@code security.password.*}
     */
    public PasswordValidator(final PasswordPolicyProperties policy) {
        this.policy = policy;
    }

    /**
     * Checks the password against every policy rule.
     *
     * @param password the raw password to validate ({@code null} is valid)
     * @param context  validator context (unused — single global message)
     * @return {@code true} when all policy rules are satisfied
     */
    @Override
    public boolean isValid(final String password, final ConstraintValidatorContext context) {
        if (password == null) {
            return true;
        }
        if (password.length() < policy.minLength()) {
            return false;
        }

        int uppercase = 0;
        int digits = 0;
        int special = 0;
        int i = 0;
        while (i < password.length()) {
            final int cp = password.codePointAt(i);
            if (Character.isUpperCase(cp)) {
                uppercase++;
            } else if (Character.isDigit(cp)) {
                digits++;
            } else if (!Character.isLetterOrDigit(cp)) {
                special++;
            }
            i += Character.charCount(cp);
        }

        return uppercase >= policy.minUppercase()
            && digits >= policy.minDigits()
            && special >= policy.minSpecial();
    }
}
