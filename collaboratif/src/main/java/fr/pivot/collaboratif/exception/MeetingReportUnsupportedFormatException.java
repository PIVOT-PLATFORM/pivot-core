package fr.pivot.collaboratif.exception;

/**
 * HTTP 400 thrown when {@code GET .../report/export} is called with a {@code format} query
 * parameter other than {@code json} or {@code markdown} (US12.3.1 AC error case) — e.g. {@code
 * format=xml}. Carries the rejected value so the response detail can name it explicitly.
 */
public class MeetingReportUnsupportedFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for a rejected export format.
     *
     * @param format the unsupported {@code format} value as received
     */
    public MeetingReportUnsupportedFormatException(final String format) {
        super("Unsupported export format: '" + format + "' (expected 'json' or 'markdown')");
    }
}
