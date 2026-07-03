package fr.pivot.auth.validation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Password robustness policy (US01.2.4), bound from {@code security.password.*}.
 *
 * <p>The four parameters below are the <strong>exhaustive</strong> list of configurable
 * rules — no implicit parameter exists. The same values are exposed to the frontend via
 * {@code GET /auth/password-policy} so both sides always validate against the same policy.
 *
 * @param minLength    minimum password length in UTF-16 units (default 12)
 * @param minUppercase minimum number of uppercase letters (default 1)
 * @param minDigits    minimum number of digits (default 1)
 * @param minSpecial   minimum number of special characters — neither letter nor digit (default 1)
 */
@ConfigurationProperties("security.password")
public record PasswordPolicyProperties(
    @DefaultValue("12") int minLength,
    @DefaultValue("1") int minUppercase,
    @DefaultValue("1") int minDigits,
    @DefaultValue("1") int minSpecial
) {}
