package fr.pivot.agilite.poker;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Card decks available to planning poker rooms (E09 — full classic parity).
 *
 * <p>v1 shipped a single hardcoded Fibonacci deck (ADR-026 §2). The classic experience lets the
 * facilitator pick the estimation scale at room creation, so this now offers three fixed decks —
 * {@link #SEQUENCE_FIBONACCI}, {@link #SEQUENCE_FIBONACCI_SIMPLE} and {@link #SEQUENCE_TSHIRT} —
 * still server-owned (a room's deck is chosen once at creation and never mutated; the caller may
 * only pick one of these identifiers, never supply arbitrary card values). Fully custom decks
 * remain out of scope.
 *
 * <p>The {@code sequence} identifier is persisted on every {@link PokerRoom}; {@link
 * #valuesFor(String)} maps it back to the ordered card values returned by the API (create/join
 * responses) — the single source of truth, never re-derived per call site.
 */
public final class PokerCardDeck {

    /** Full Fibonacci scale — the historical v1 deck and the default when none is chosen. */
    public static final String SEQUENCE_FIBONACCI = "FIBONACCI";

    /** Simplified Fibonacci scale (rounded high end + {@code ½}). */
    public static final String SEQUENCE_FIBONACCI_SIMPLE = "FIBONACCI_SIMPLE";

    /** T-shirt sizing scale. */
    public static final String SEQUENCE_TSHIRT = "TSHIRT";

    /** Deck applied when a create request omits the deck — preserves the historical behaviour. */
    public static final String DEFAULT_SEQUENCE = SEQUENCE_FIBONACCI;

    /** Card values for {@link #SEQUENCE_FIBONACCI}, in play order. */
    public static final List<String> FIBONACCI_VALUES =
            List.of("0", "1", "2", "3", "5", "8", "13", "21", "34", "55", "89", "?");

    /** Card values for {@link #SEQUENCE_FIBONACCI_SIMPLE}, in play order ({@code ½} = U+00BD). */
    public static final List<String> FIBONACCI_SIMPLE_VALUES =
            List.of("0", "½", "1", "2", "3", "5", "8", "13", "20", "40", "100", "?");

    /** Card values for {@link #SEQUENCE_TSHIRT}, in play order. */
    public static final List<String> TSHIRT_VALUES = List.of("XS", "S", "M", "L", "XL", "XXL", "?");

    private static final Map<String, List<String>> DECKS = Map.of(
            SEQUENCE_FIBONACCI, FIBONACCI_VALUES,
            SEQUENCE_FIBONACCI_SIMPLE, FIBONACCI_SIMPLE_VALUES,
            SEQUENCE_TSHIRT, TSHIRT_VALUES);

    private PokerCardDeck() {
    }

    /**
     * @param sequence a deck identifier
     * @return {@code true} if {@code sequence} is one of the supported decks
     */
    public static boolean isSupported(final String sequence) {
        return sequence != null && DECKS.containsKey(sequence);
    }

    /** @return the immutable set of supported deck identifiers */
    public static Set<String> supportedSequences() {
        return DECKS.keySet();
    }

    /**
     * Maps a persisted deck identifier to its ordered card values.
     *
     * @param sequence the deck identifier stored on the room
     * @return the ordered card values for that deck
     * @throws IllegalArgumentException if {@code sequence} is not a supported deck — a
     *     programming error at this point, since the value is validated at room creation and the
     *     DB {@code CHECK} constraint forbids any other value
     */
    public static List<String> valuesFor(final String sequence) {
        final List<String> values = DECKS.get(sequence);
        if (values == null) {
            throw new IllegalArgumentException("Unknown planning poker deck: " + sequence);
        }
        return values;
    }
}
