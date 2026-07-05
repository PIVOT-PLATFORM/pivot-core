package fr.pivot.account.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour {@link HtmlStripper} (US02.1.1 — XSS protection sur prénom/nom).
 */
class HtmlStripperTest {

    @Test
    void ac0211_xss01_stripsWellFormedTags_keepingInnerText() {
        assertThat(HtmlStripper.stripTags("<b>Bob</b>")).isEqualTo("Bob");
    }

    @Test
    void ac0211_xss02_stripsScriptTag_keepingInnerText() {
        assertThat(HtmlStripper.stripTags("<script>alert(1)</script>Bob"))
                .isEqualTo("alert(1)Bob");
    }

    @Test
    void ac0211_xss03_stripsResidualUnclosedAngleBracket() {
        // Malformed / truncated tag — no closing '>' for the TAG_PATTERN to match.
        assertThat(HtmlStripper.stripTags("<img src=x onerror=alert(1) Bob"))
                .isEqualTo("img src=x onerror=alert(1) Bob");
    }

    @Test
    void ac0211_xss04_leavesPlainTextUnchanged() {
        assertThat(HtmlStripper.stripTags("Jean-Michel")).isEqualTo("Jean-Michel");
    }

    @Test
    void ac0211_xss05_returnsNull_whenInputIsNull() {
        assertThat(HtmlStripper.stripTags(null)).isNull();
    }

    @Test
    void ac0211_xss06_resultsInBlank_whenInputIsOnlyTags() {
        assertThat(HtmlStripper.stripTags("<script></script>")).isBlank();
    }
}
