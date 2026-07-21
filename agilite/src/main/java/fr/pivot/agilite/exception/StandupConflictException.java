package fr.pivot.agilite.exception;

/**
 * Thrown for HTTP 409 standup lifecycle conflicts (US10.1.1/US10.1.2/US10.2.2): an action
 * attempted while the session/participant is not in the required status ({@code
 * INVALID_SESSION_STATUS} — e.g. {@code start} on an already-{@code RUNNING} session, {@code
 * next}/{@code end}/{@code skip}/{@code extend} on a non-{@code RUNNING} session), or a {@code
 * DELETE} attempted on a currently {@code RUNNING} session ({@code SESSION_RUNNING}).
 *
 * <p>Mapped to HTTP 409, mirroring the code-carrying shape of {@link StandupValidationException}
 * but for conflict (not validation) failures — same distinction the poker module draws between
 * {@code TicketAlreadyRevealedException}/{@code TicketNotRevealedException} (409) and {@code
 * WheelValidationException} (400).
 */
public class StandupConflictException extends RuntimeException {

    private final String code;

    /**
     * Creates a conflict exception carrying a machine-readable code.
     *
     * @param code    the machine-readable error code (e.g. {@code "INVALID_SESSION_STATUS"})
     * @param message a human-readable detail message
     */
    public StandupConflictException(final String code, final String message) {
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
