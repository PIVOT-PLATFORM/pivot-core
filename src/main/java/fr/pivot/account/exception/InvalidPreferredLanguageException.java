package fr.pivot.account.exception;

/**
 * Thrown when a {@code PATCH /account/profile} request carries a {@code preferredLanguage}
 * value other than {@code fr}/{@code en} (case-insensitive) (US02.1.2). Translated to
 * {@code 400 Bad Request} by {@code AccountController}.
 */
public class InvalidPreferredLanguageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception with a user-facing message.
     *
     * @param message explanation of why the value is invalid
     */
    public InvalidPreferredLanguageException(final String message) {
        super(message);
    }
}
