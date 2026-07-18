package fr.pivot.collaboratif.whiteboard;

import java.util.regex.Pattern;

/**
 * Neutralises user-controlled values before they are written to application logs.
 *
 * <p>The whiteboard STOMP handlers log rejected or unknown inbound payload fields (the raw
 * action type, a participant's display name, an invalid board-field type…) to aid operational
 * triage. Those values originate from the client and may contain CR/LF or other control
 * characters, which enables log forging (CWE-117, Sonar {@code javasecurity:S5145}): an
 * attacker could smuggle newlines to inject fake log lines. {@link #forLog(Object)} collapses
 * every Unicode control character to {@code _} and caps the length, so a logged value can never
 * break out of its own line nor flood the log.
 */
public final class LogSanitizer {

    /** Hard cap on a single logged value — long enough for diagnostics, short enough to bound log size. */
    private static final int MAX_LENGTH = 256;

    /** Unicode {@code Cc} category — the C0 (incl. CR, LF, TAB) and C1 control ranges. */
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cc}");

    private LogSanitizer() {
    }

    /**
     * Returns a single-line, length-bounded representation of {@code value} that is safe to embed
     * in a log statement. Control characters (CR, LF, TAB and the rest of the C0/C1 range) are
     * replaced by {@code _}; a {@code null} becomes the literal {@code "null"}.
     *
     * @param value the raw, possibly user-controlled value (may be {@code null})
     * @return a sanitized, log-safe string
     */
    public static String forLog(final Object value) {
        if (value == null) {
            return "null";
        }
        String text = value.toString();
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH) + "…";
        }
        return CONTROL_CHARS.matcher(text).replaceAll("_");
    }
}
