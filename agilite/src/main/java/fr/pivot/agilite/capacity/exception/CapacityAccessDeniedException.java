package fr.pivot.agilite.capacity.exception;

/**
 * Thrown when the caller is a member of the capacity event's team (so the event's existence is
 * not hidden from them) but lacks the role required for the attempted write operation (E11 —
 * roles OWNER/EDITOR/VIEWER enforced in service). Mapped to HTTP 403 by
 * {@code CapacityExceptionHandler}.
 *
 * <p>Contrast with {@link CapacityEventNotFoundException}, which is used for callers of another
 * tenant or non-members: those get a 404 to avoid confirming cross-tenant existence.
 */
public class CapacityAccessDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception with a message describing the required role.
     *
     * @param message a human-readable description of the denied operation / required role
     */
    public CapacityAccessDeniedException(final String message) {
        super(message);
    }
}
