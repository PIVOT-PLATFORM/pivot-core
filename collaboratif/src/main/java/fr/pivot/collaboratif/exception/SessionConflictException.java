package fr.pivot.collaboratif.exception;

/**
 * Generic HTTP 409 for a session-domain business-rule conflict that carries a machine-readable
 * {@code code} (e.g. {@code WORD_LIMIT_REACHED}) — mirrors {@link SessionValidationException} but
 * for conflicts rather than plain bad input.
 */
public class SessionConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * Creates the exception with its machine-readable error code.
     *
     * @param code    the error code (e.g. {@code "WORD_LIMIT_REACHED"})
     * @param message a human-readable description
     */
    public SessionConflictException(final String code, final String message) {
        super(message);
        this.code = code;
    }

    /**
     * Returns the machine-readable error code.
     *
     * @return the code
     */
    public String getCode() {
        return code;
    }
}
