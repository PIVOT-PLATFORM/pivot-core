package fr.pivot.collaboratif.exception;

/**
 * Generic HTTP 400 for a session-domain business-rule validation failure that cannot be expressed
 * as a simple Bean Validation constraint (e.g. {@code INVALID_POLL_VOTE}, {@code INVALID_WORD},
 * {@code WORD_BLOCKED}) — carries a machine-readable {@code code}, mirroring this module's
 * existing {@code InvalidActivityException}/{@code InvalidInvitationException} pattern.
 */
public class SessionValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * Creates the exception with its machine-readable error code.
     *
     * @param code    the error code (e.g. {@code "INVALID_POLL_VOTE"})
     * @param message a human-readable description
     */
    public SessionValidationException(final String code, final String message) {
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
