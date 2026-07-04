package fr.pivot.account.exception;

/**
 * Thrown when {@code firstName}/{@code lastName} become blank after HTML stripping
 * (US02.1.1) — e.g. a value consisting entirely of markup such as {@code "<script></script>"}.
 * Translated to {@code 400 Bad Request} by {@code AccountController}.
 */
public class InvalidProfileNameException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception with a user-facing message.
     *
     * @param message explanation of why the name is invalid
     */
    public InvalidProfileNameException(final String message) {
        super(message);
    }
}
