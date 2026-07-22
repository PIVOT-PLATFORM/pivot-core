package fr.pivot.agilite.exception;

/**
 * Thrown for PI Planning business-rule validation failures that require a database lookup or
 * cross-field logic and therefore can't be expressed as simple bean validation annotations
 * (US50.1.1/US50.3.1/US50.3.2): out-of-bounds iteration parameters ({@code
 * INVALID_ITERATION_PARAMS}), an invalid iteration date range ({@code INVALID_DATE_RANGE}), a
 * team import batch with nothing importable ({@code NO_IMPORTABLE_TEAM}), an out-of-cycle board
 * cell ({@code INVALID_CELL}), a self-dependency ({@code SELF_DEPENDENCY}), a cross-cycle ticket
 * reference ({@code INVALID_TICKET}), a duplicate dependency pair ({@code
 * DUPLICATE_DEPENDENCY}), or a cycle-forming dependency ({@code DEPENDENCY_CYCLE}).
 *
 * <p>Mapped to HTTP 400, mirroring {@code WheelValidationException}/{@code
 * StandupValidationException}'s code-carrying shape.
 */
public class PiValidationException extends RuntimeException {

    private final String code;

    /**
     * Creates a validation exception carrying a machine-readable code.
     *
     * @param code    the machine-readable error code (e.g. {@code "INVALID_CELL"})
     * @param message a human-readable detail message
     */
    public PiValidationException(final String code, final String message) {
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
