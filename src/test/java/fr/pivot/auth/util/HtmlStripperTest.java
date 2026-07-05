package fr.pivot.auth.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HtmlStripper} (US02.2.3 — «appareil» field sanitization).
 */
class HtmlStripperTest {

    @Test
    void stripAndTruncate_returnsNull_whenInputNull() {
        assertThat(HtmlStripper.stripAndTruncate(null, 200)).isNull();
    }

    @Test
    void stripAndTruncate_leavesPlainTextUnchanged() {
        assertThat(HtmlStripper.stripAndTruncate("Chrome sur Windows", 200)).isEqualTo("Chrome sur Windows");
    }

    @Test
    void stripAndTruncate_removesTags_keepingTextContent() {
        assertThat(HtmlStripper.stripAndTruncate("<script>evil()</script>Chrome", 200)).isEqualTo("evil()Chrome");
    }

    @Test
    void stripAndTruncate_removesSelfClosingAndMalformedLookingTags() {
        assertThat(HtmlStripper.stripAndTruncate("Chrome<img src=x onerror=alert(1)>", 200)).isEqualTo("Chrome");
    }

    @Test
    void stripAndTruncate_trimsWhitespace_afterStripping() {
        assertThat(HtmlStripper.stripAndTruncate("  <b>Chrome</b>  ", 200)).isEqualTo("Chrome");
    }

    @Test
    void stripAndTruncate_truncatesToMaxLength() {
        final String longValue = "a".repeat(250);
        final String result = HtmlStripper.stripAndTruncate(longValue, 200);
        assertThat(result).hasSize(200);
        assertThat(result).isEqualTo("a".repeat(200));
    }

    @Test
    void stripAndTruncate_leavesShortValueUntouched_atExactMaxLength() {
        final String exact = "a".repeat(200);
        assertThat(HtmlStripper.stripAndTruncate(exact, 200)).hasSize(200);
    }

    @Test
    void stripAndTruncate_returnsEmptyString_whenOnlyTags() {
        assertThat(HtmlStripper.stripAndTruncate("<div></div>", 200)).isEmpty();
    }

    @Test
    void stripAndTruncate_removesUnterminatedTag_withNoClosingBracket() {
        // TAG_PATTERN alone only matches well-formed "<...>" spans — an unterminated tag (no
        // closing '>' anywhere in the value) would otherwise sail through untouched and could
        // still be reinterpreted as markup by a lenient downstream HTML parser.
        final String result = HtmlStripper.stripAndTruncate("Chrome<img src=x onerror=alert(1)", 200);
        assertThat(result).doesNotContain("<").doesNotContain(">");
    }

    @Test
    void stripAndTruncate_removesLoneAngleBrackets_notFormingAnyTag() {
        final String result = HtmlStripper.stripAndTruncate("5 < 10 > 3", 200);
        assertThat(result).doesNotContain("<").doesNotContain(">");
    }
}
