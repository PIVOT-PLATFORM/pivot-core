package fr.pivot.auth.exception;

/**
 * Thrown by {@code GET /account/email/confirm} (US02.2.2) when the supplied confirmation
 * token cannot be honoured.
 *
 * <p>The three {@link Reason}s are translated to distinct HTTP statuses and {@code code}
 * values by the global exception handler so the frontend can render the right page —
 * notably {@link Reason#ALREADY_USED}, which the AC mandates as {@code 410 Gone} for a
 * second click on an already-consumed (or superseded) link, and {@link Reason#EXPIRED},
 * which routes to a dedicated "expired link, request a new one" screen rather than a
 * generic error.
 */
public class EmailChangeTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the token was rejected. */
    public enum Reason {
        /** No row matches this token's hash at all. */
        INVALID,
        /** The row exists, is not yet terminal, but its TTL has elapsed. */
        EXPIRED,
        /** The row was already consumed by a prior confirmation, or cancelled by a newer request. */
        ALREADY_USED
    }

    private final Reason reason;

    /**
     * @param reason why the token was rejected
     */
    public EmailChangeTokenException(final Reason reason) {
        super("Email change confirmation token rejected: " + reason);
        this.reason = reason;
    }

    /** @return why the token was rejected */
    public Reason getReason() {
        return reason;
    }
}
