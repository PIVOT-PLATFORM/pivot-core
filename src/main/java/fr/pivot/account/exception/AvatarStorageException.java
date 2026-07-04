package fr.pivot.account.exception;

/**
 * Thrown when the local filesystem avatar store fails for an infrastructure reason
 * (disk full, permission denied…) — not a user input error. Left untranslated by
 * {@code AccountController} (no local {@code @ExceptionHandler}), so it falls through to
 * Spring Boot's default {@code /error} handling ({@code 500}, generic body, no internal
 * detail leaked).
 */
public class AvatarStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception wrapping the underlying I/O failure.
     *
     * @param message human-readable summary
     * @param cause   the underlying {@link java.io.IOException}
     */
    public AvatarStorageException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
