package fr.pivot.agilite.exception;

/**
 * Thrown for standup business-rule validation failures that require a database lookup or
 * cross-field logic and therefore can't be expressed as simple bean validation annotations
 * (US10.1.1/US10.2.2/US10.3.1): an invalid {@code timePerPersonSeconds} bound, a {@code
 * participantTeamMemberId} that isn't a member of the session's team ({@code INVALID_PARTICIPANT}),
 * an {@code extend} request outside {@code {30, 60}} ({@code INVALID_EXTEND_SECONDS}), a {@code
 * reorder} request that doesn't exactly match the currently {@code WAITING} participants ({@code
 * INVALID_REORDER}), or a stats request with {@code from} after {@code to} ({@code
 * INVALID_DATE_RANGE}).
 *
 * <p>Mapped to HTTP 400, mirroring {@code WheelValidationException}'s code-carrying shape.
 */
public class StandupValidationException extends RuntimeException {

    private final String code;

    /**
     * Creates a validation exception carrying a machine-readable code.
     *
     * @param code    the machine-readable error code (e.g. {@code "INVALID_PARTICIPANT"})
     * @param message a human-readable detail message
     */
    public StandupValidationException(final String code, final String message) {
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
