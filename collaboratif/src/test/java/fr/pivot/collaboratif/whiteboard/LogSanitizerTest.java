package fr.pivot.collaboratif.whiteboard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LogSanitizer} — the log-forging (CWE-117 / Sonar S5145) neutraliser
 * applied to user-controlled values before they reach the whiteboard STOMP logs.
 */
class LogSanitizerTest {

    private static final char TAB = (char) 0x09;
    private static final char CR = (char) 0x0D;
    private static final char LF = (char) 0x0A;
    private static final char NUL = (char) 0x00;

    @Test
    void forLog_nullValue_returnsLiteralNull() {
        assertThat(LogSanitizer.forLog(null)).isEqualTo("null");
    }

    @Test
    void forLog_plainValue_isUnchanged() {
        assertThat(LogSanitizer.forLog("card:create")).isEqualTo("card:create");
    }

    @Test
    void forLog_stripsCarriageReturnAndLineFeed_preventingLogForging() {
        String forged = "legit" + CR + LF + "2026-01-01 ERROR fake-admin-login-succeeded";
        String sanitized = LogSanitizer.forLog(forged);
        assertThat(sanitized).doesNotContain("\r").doesNotContain("\n");
        assertThat(sanitized).isEqualTo("legit__2026-01-01 ERROR fake-admin-login-succeeded");
    }

    @Test
    void forLog_stripsTabAndNul_butKeepsSpace() {
        String input = "a" + TAB + "b c" + NUL + "d";
        assertThat(LogSanitizer.forLog(input)).isEqualTo("a_b c_d");
    }

    @Test
    void forLog_nonStringValue_usesToString() {
        assertThat(LogSanitizer.forLog(42)).isEqualTo("42");
    }

    @Test
    void forLog_overlongValue_isTruncatedAndMarked() {
        String result = LogSanitizer.forLog("x".repeat(300));
        assertThat(result).hasSize(257).endsWith("…");
        assertThat(result).startsWith("x".repeat(256));
    }
}
