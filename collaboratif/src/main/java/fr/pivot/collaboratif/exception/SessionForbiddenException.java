package fr.pivot.collaboratif.exception;

/**
 * HTTP 403 for a session-domain action the caller is authenticated and joined for, but not
 * permitted on this specific resource — e.g. editing or deleting another participant's BRAINSTORM
 * card (US19.3.4).
 *
 * <p>Distinct from {@link SessionNotFoundException} (404, anti-enumeration): session <em>access</em>
 * denials stay 404 so a caller cannot probe which sessions exist, but once inside a session a
 * participant legitimately knows a card exists — hiding a per-card ownership denial as a 404 would
 * be misleading, and the AC calls for a 403 here explicitly.
 */
public class SessionForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * Creates the exception with its machine-readable error code.
     *
     * @param code    the error code (e.g. {@code "NOT_CARD_OWNER"})
     * @param message a human-readable description
     */
    public SessionForbiddenException(final String code, final String message) {
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
