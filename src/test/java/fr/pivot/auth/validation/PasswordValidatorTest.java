package fr.pivot.auth.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PasswordValidator} (US01.2.4).
 *
 * <p>Traceability: AC "Validation backend : min 12 chars, 1 majuscule, 1 chiffre, 1 spécial"
 * and AC "TU PasswordValidator (limites : 11/12/13 chars, chaque critère isolé, unicode)".
 */
class PasswordValidatorTest {

    private PasswordValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PasswordValidator(new PasswordPolicyProperties(12, 1, 1, 1));
    }

    private boolean valid(final String password) {
        return validator.isValid(password, null);
    }

    // ----------------------------------------------------------------
    // Length boundaries — 11 / 12 / 13 chars (all other criteria met)
    // ----------------------------------------------------------------

    @Test
    void ac0124_01_rejects11Chars_evenWithAllOtherCriteria() {
        assertThat("Abcdefgh1!x").hasSize(11);
        assertThat(valid("Abcdefgh1!x")).isFalse();
    }

    @Test
    void ac0124_01_accepts12Chars_withAllCriteria() {
        assertThat("Abcdefghi1!x").hasSize(12);
        assertThat(valid("Abcdefghi1!x")).isTrue();
    }

    @Test
    void ac0124_01_accepts13Chars_withAllCriteria() {
        assertThat("Abcdefghij1!x").hasSize(13);
        assertThat(valid("Abcdefghij1!x")).isTrue();
    }

    // ----------------------------------------------------------------
    // Each criterion isolated (length OK, one criterion missing)
    // ----------------------------------------------------------------

    @Test
    void ac0124_01_rejectsMissingUppercase_only() {
        assertThat(valid("abcdefghij1!")).isFalse();
    }

    @Test
    void ac0124_01_rejectsMissingDigit_only() {
        assertThat(valid("Abcdefghijk!")).isFalse();
    }

    @Test
    void ac0124_01_rejectsMissingSpecial_only() {
        assertThat(valid("Abcdefghijk1")).isFalse();
    }

    @Test
    void ac0124_01_nullIsValid_notBlankOwnsThatRule() {
        assertThat(valid(null)).isTrue();
    }

    // ----------------------------------------------------------------
    // Unicode awareness
    // ----------------------------------------------------------------

    @Test
    void ac0124_01_unicodeUppercaseCounts() {
        // 'É' is an uppercase letter — satisfies the uppercase criterion
        assertThat(valid("Épicerie123!")).isTrue();
        assertThat(valid("épicerie123!")).isFalse();
    }

    @Test
    void ac0124_01_unicodeDigitCounts() {
        // Arabic-Indic digit '٣' (U+0663) satisfies the digit criterion
        assertThat(valid("Abcdefghij٣!")).isTrue();
    }

    @Test
    void ac0124_01_emojiCountsAsSpecial() {
        // Emoji (surrogate pair) is neither letter nor digit → special
        assertThat(valid("Abcdefghi1😀")).isTrue();
    }

    @Test
    void ac0124_01_accentedLowercaseIsNotSpecial() {
        // 'é' is a letter — must NOT satisfy the special-char criterion
        assertThat(valid("Abcdefghié12")).isFalse();
    }

    // ----------------------------------------------------------------
    // Configurable policy — exhaustive parameters (AC configurabilité)
    // ----------------------------------------------------------------

    @Test
    void ac0124_02_configurableMinima_areEnforced() {
        final PasswordValidator strict =
            new PasswordValidator(new PasswordPolicyProperties(16, 2, 2, 2));

        assertThat(strict.isValid("Abcdefghijk1!xyz", null)).isFalse();  // 1 upper, 1 digit, 1 special
        assertThat(strict.isValid("ABcdefghij12!?xy", null)).isTrue();   // 2 of each, 16 chars
        assertThat(strict.isValid("ABcdefghij12!?x", null)).isFalse();   // 15 chars < 16
    }
}
