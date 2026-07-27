package fr.pivot.collaboratif.exception;

/**
 * Generic HTTP 409 for a meeting-domain lifecycle conflict that carries a machine-readable
 * {@code code} — {@code start} on an already {@code IN_PROGRESS}/{@code ENDED} meeting (US12.2.1
 * AC-E1), or {@code agenda/next}/{@code end}/{@code actions} on a meeting not currently {@code
 * IN_PROGRESS} (AC-E2). Mirrors {@link SessionConflictException}'s identical shape for the
 * Module Session domain.
 */
public class MeetingConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * Creates the exception with its machine-readable error code.
     *
     * @param code    the error code (e.g. {@code "MEETING_ALREADY_IN_PROGRESS"})
     * @param message a human-readable description
     */
    public MeetingConflictException(final String code, final String message) {
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
