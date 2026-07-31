package fr.pivot.collaboratif.bingo;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BingoInviteCodeGenerator} (US47.1.1, AC-47.1.1-01).
 */
class BingoInviteCodeGeneratorTest {

    @RepeatedTest(50)
    void generate_alwaysReturnsSixCharactersFromTheAlphabet() {
        String code = BingoInviteCodeGenerator.generate();

        assertThat(code).hasSize(6);
        assertThat(code).matches("[" + BingoInviteCodeGenerator.ALPHABET + "]{6}");
    }

    @Test
    void generate_alphabetExcludesAmbiguousCharacters() {
        assertThat(BingoInviteCodeGenerator.ALPHABET).doesNotContain("0", "O", "1", "I");
    }

    @Test
    void generate_producesDistinctCodesAcrossManyCalls() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            codes.add(BingoInviteCodeGenerator.generate());
        }
        // Astronomically unlikely to collide at this sample size (32^6 combinations) — a
        // regression to a tiny/fixed alphabet would show up as far fewer distinct values.
        assertThat(codes).hasSizeGreaterThan(190);
    }
}
