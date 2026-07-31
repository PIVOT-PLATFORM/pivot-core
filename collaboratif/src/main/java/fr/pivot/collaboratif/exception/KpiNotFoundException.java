package fr.pivot.collaboratif.exception;

/**
 * Thrown when a {@code kpiKey} does not resolve to a known KPI, or when a {@code teamId} scope
 * does not resolve to a team of the caller's own tenant (EN19.4). Both causes deliberately share
 * this single 404 — same anti-enumeration posture as {@link SessionNotFoundException} — so a
 * caller cannot distinguish "no such KPI" from "that team belongs to another tenant".
 */
public class KpiNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message a human-readable description (never echoed with tenant-identifying detail)
     */
    public KpiNotFoundException(final String message) {
        super(message);
    }
}
