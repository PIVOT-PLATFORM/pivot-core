package fr.pivot.agilite.poker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit tests for the {@link PokerCardDeck} deck registry (E09 — deck choice). */
class PokerCardDeckTest {

    @Test
    void valuesFor_eachSupportedDeck_returnsItsOrderedCards() {
        assertThat(PokerCardDeck.valuesFor(PokerCardDeck.SEQUENCE_FIBONACCI))
                .isEqualTo(PokerCardDeck.FIBONACCI_VALUES);
        assertThat(PokerCardDeck.valuesFor(PokerCardDeck.SEQUENCE_FIBONACCI_SIMPLE))
                .isEqualTo(PokerCardDeck.FIBONACCI_SIMPLE_VALUES);
        assertThat(PokerCardDeck.valuesFor(PokerCardDeck.SEQUENCE_TSHIRT))
                .isEqualTo(PokerCardDeck.TSHIRT_VALUES);
    }

    @Test
    void isSupported_distinguishesKnownUnknownAndNull() {
        assertThat(PokerCardDeck.isSupported(PokerCardDeck.SEQUENCE_TSHIRT)).isTrue();
        assertThat(PokerCardDeck.isSupported("NOT_A_DECK")).isFalse();
        assertThat(PokerCardDeck.isSupported(null)).isFalse();
    }

    @Test
    void supportedSequences_areExactlyTheThreeDecks() {
        assertThat(PokerCardDeck.supportedSequences())
                .containsExactlyInAnyOrder(
                        PokerCardDeck.SEQUENCE_FIBONACCI,
                        PokerCardDeck.SEQUENCE_FIBONACCI_SIMPLE,
                        PokerCardDeck.SEQUENCE_TSHIRT);
    }

    @Test
    void valuesFor_unknownDeck_throwsIllegalArgument() {
        assertThatThrownBy(() -> PokerCardDeck.valuesFor("NOT_A_DECK"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultSequence_isFibonacci() {
        assertThat(PokerCardDeck.DEFAULT_SEQUENCE).isEqualTo(PokerCardDeck.SEQUENCE_FIBONACCI);
    }
}
