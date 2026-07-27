package fr.pivot.collaboratif.exception;

/**
 * HTTP 409 for a booking-flow state conflict — most notably a confirm attempted on a meeting that
 * is already {@code CONFIRMED} (US12.4.1 "Error — validation d'un créneau invalide / double
 * confirmation"). Carries a machine-readable {@code code}, mirroring this module's existing
 * {@code SessionConflictException} pattern.
 */
public class MeetingConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * Creates the exception with its machine-readable error code.
     *
     * @param code    the error code (e.g. {@code "ALREADY_CONFIRMED"})
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
