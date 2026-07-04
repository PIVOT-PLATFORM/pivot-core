package fr.pivot.account.exception;

/**
 * Thrown when a {@code PATCH /account/profile} request body contains an {@code email}
 * property (US02.1.1 — email changes are out of scope, handled by a dedicated
 * re-verification flow, US02.2.2). Translated to {@code 400 Bad Request} by
 * {@code AccountController}.
 */
public class EmailFieldNotAllowedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception with a fixed, user-facing message.
     */
    public EmailFieldNotAllowedException() {
        super("Email cannot be changed via PATCH /account/profile");
    }
}
