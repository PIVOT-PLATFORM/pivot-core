package fr.pivot.account.exception;

/**
 * Thrown when an uploaded avatar is empty or is not one of the accepted formats
 * (JPEG, PNG, WEBP) — detected by magic-byte sniffing of the file content, never by trusting
 * the client-supplied {@code Content-Type} header alone (US02.1.1). Translated to
 * {@code 400 Bad Request} by {@code AccountController}.
 */
public class InvalidAvatarFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception with a diagnostic detail (not shown to end users as-is —
     * the controller returns a localized, generic message).
     *
     * @param detail internal diagnostic detail (e.g. declared content type, or "empty")
     */
    public InvalidAvatarFormatException(final String detail) {
        super("Invalid avatar format: " + detail);
    }
}
