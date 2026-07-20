package fr.pivot.collaboratif.exception;

/**
 * Thrown when an invitation is syntactically valid but not allowed for a business reason
 * (US08.2.5): the OWNER inviting their own e-mail. Mapped to HTTP 400 with a machine-readable
 * {@code code} so the frontend can announce a precise, localized error.
 */
public class InvalidInvitationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Machine-readable error code (currently only {@code SELF_INVITE}). */
    private final String code;

    /**
     * Creates the exception.
     *
     * @param code    the machine-readable error code
     * @param message the human-readable French detail
     */
    public InvalidInvitationException(final String code, final String message) {
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
