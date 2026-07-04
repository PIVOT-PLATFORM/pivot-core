package fr.pivot.account.exception;

/**
 * Thrown when an uploaded avatar exceeds the 2&nbsp;MB limit (US02.1.1).
 * Translated to {@code 400 Bad Request} by {@code AccountController}.
 */
public class AvatarTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception for a rejected upload of the given size.
     *
     * @param sizeBytes size of the rejected file, in bytes
     */
    public AvatarTooLargeException(final long sizeBytes) {
        super("Avatar file too large: " + sizeBytes + " bytes (max 2097152 bytes / 2 MB)");
    }
}
