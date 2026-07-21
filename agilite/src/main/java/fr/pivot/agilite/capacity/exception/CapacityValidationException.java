package fr.pivot.agilite.capacity.exception;

/**
 * Thrown when a capacity request is structurally valid (passes bean validation) but violates a
 * domain rule (E11) — e.g. an end date before the start date, a focus factor outside
 * {@code [0,1]}, an absence period outside its event's window, a hierarchy nesting deeper than
 * the allowed two levels, or a parent event of an incompatible type. Mapped to HTTP 400 by
 * {@code CapacityExceptionHandler}, carrying a machine-readable {@code code} property so the
 * frontend can localise the message.
 */
public class CapacityValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * Constructs the exception with a machine-readable code and a human-readable message.
     *
     * @param code    a stable, machine-readable error code (e.g. {@code "INVALID_DATE_RANGE"},
     *                {@code "FOCUS_OUT_OF_RANGE"}, {@code "HIERARCHY_TOO_DEEP"})
     * @param message a human-readable description of the violated rule
     */
    public CapacityValidationException(final String code, final String message) {
        super(message);
        this.code = code;
    }

    /**
     * Returns the machine-readable error code exposed to the client as the {@code code} property
     * of the RFC 7807 problem detail.
     *
     * @return the stable error code
     */
    public String getCode() {
        return code;
    }
}
