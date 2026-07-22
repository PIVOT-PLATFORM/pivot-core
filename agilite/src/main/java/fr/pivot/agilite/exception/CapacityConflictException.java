package fr.pivot.agilite.exception;

/**
 * Thrown for HTTP 409 capacity event lifecycle conflicts (US11.1.1): deleting an event that
 * still has children ({@code EVENT_HAS_CHILDREN}) — the children must be deleted first.
 *
 * <p>Mapped to HTTP 409, mirroring the code-carrying shape of {@code
 * StandupConflictException}/{@code CapacityValidationException} but for conflict (not
 * validation) failures.
 */
public class CapacityConflictException extends RuntimeException {

    private final String code;

    /**
     * Creates a conflict exception carrying a machine-readable code.
     *
     * @param code    the machine-readable error code (e.g. {@code "EVENT_HAS_CHILDREN"})
     * @param message a human-readable detail message
     */
    public CapacityConflictException(final String code, final String message) {
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
