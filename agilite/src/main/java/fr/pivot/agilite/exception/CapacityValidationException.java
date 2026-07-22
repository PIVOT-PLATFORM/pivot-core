package fr.pivot.agilite.exception;

/**
 * Thrown for capacity-planning business-rule validation failures that require a database lookup
 * or cross-field logic and therefore can't be expressed as simple bean validation annotations
 * (US11.1.1/US11.2.1/US11.2.2/US11.3.1/US11.4.1/US11.4.2): invalid date range ({@code
 * INVALID_DATE_RANGE}), invalid event type ({@code INVALID_EVENT_TYPE}), an invalid parent event
 * reference ({@code INVALID_PARENT_EVENT}), a max-depth-exceeded parent chain ({@code
 * MAX_DEPTH_EXCEEDED}), an out-of-range availability percentage ({@code INVALID_AVAILABILITY}),
 * an absence entirely outside its event's period ({@code ABSENCE_OUTSIDE_EVENT}), invalid
 * velocity points ({@code INVALID_POINTS}), a velocity/burndown action attempted on the wrong
 * event type ({@code INVALID_EVENT_TYPE_FOR_VELOCITY}/{@code INVALID_EVENT_TYPE_FOR_BURNDOWN}),
 * an out-of-bounds query parameter ({@code INVALID_QUERY_PARAM}), negative burndown points
 * ({@code INVALID_POINTS_REMAINING}), or a burndown date outside its event's period ({@code
 * DATE_OUTSIDE_EVENT}).
 *
 * <p>Mapped to HTTP 400, mirroring {@code PiValidationException}/{@code
 * StandupValidationException}'s code-carrying shape.
 */
public class CapacityValidationException extends RuntimeException {

    private final String code;

    /**
     * Creates a validation exception carrying a machine-readable code.
     *
     * @param code    the machine-readable error code (e.g. {@code "INVALID_DATE_RANGE"})
     * @param message a human-readable detail message
     */
    public CapacityValidationException(final String code, final String message) {
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
