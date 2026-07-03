package fr.pivot.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean Validation constraint enforcing the tenant-wide password robustness policy (US01.2.4).
 *
 * <p>Applied on every DTO field where a password is set (registration, password reset).
 * The rules come from {@link PasswordPolicyProperties} ({@code security.password.*})
 * and are validated by {@link PasswordValidator}.
 *
 * <p>{@code null} values are considered valid — pair with {@link jakarta.validation.constraints.NotBlank}.
 */
@Documented
@Constraint(validatedBy = PasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    /**
     * Error message when the password does not satisfy the policy.
     *
     * @return the constraint violation message
     */
    String message() default "Le mot de passe ne respecte pas la politique de robustesse";

    /**
     * Validation groups.
     *
     * @return the groups the constraint belongs to
     */
    Class<?>[] groups() default {};

    /**
     * Custom payload.
     *
     * @return the payload associated with the constraint
     */
    Class<? extends Payload>[] payload() default {};
}
