package fr.pivot.collaboratif.exception;

/**
 * HTTP 403 for a meeting-domain animation action ({@code start}/{@code agenda/next}/{@code end}/
 * {@code actions}) attempted by a caller who is authenticated and has visibility into the
 * meeting, but is neither its owner nor {@code ROLE_ADMIN} (US12.2.1 AC-S2).
 *
 * <p>Distinct from {@link MeetingNotFoundException} (404, anti-enumeration, AC-S1): tenant
 * resolution is always checked <strong>first</strong> — a meeting belonging to another tenant
 * stays a 404 regardless of role. Only once the meeting is confirmed to exist in the caller's own
 * tenant does an owner-or-admin failure become this genuine 403, exactly as AC-S2 requires
 * ("un {@code ROLE_USER} participant non-animateur → 403 Forbidden") — mirrors {@link
 * SessionForbiddenException}'s identical distinction for the Module Session domain.
 */
public class MeetingForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * Creates the exception with its machine-readable error code.
     *
     * @param code    the error code (e.g. {@code "MEETING_FACILITATOR_ONLY"})
     * @param message a human-readable description
     */
    public MeetingForbiddenException(final String code, final String message) {
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
