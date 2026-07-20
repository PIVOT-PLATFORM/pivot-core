package fr.pivot.agilite.poker.exception;

/**
 * Thrown when a planning poker room creation request supplies a {@code deck} identifier that is
 * not one of {@code PokerCardDeck}'s supported decks (E09 — classic parity, deck choice).
 *
 * <p>Mapped to HTTP 400 by {@code AgiliteExceptionHandler} with {@code { "code": "INVALID_DECK" }},
 * mirroring {@code InvalidRetroFormatException}'s posture for the retro-format field. A {@code
 * null}/blank deck is <em>not</em> an error — the service defaults it to {@code
 * PokerCardDeck.DEFAULT_SEQUENCE} — so this is thrown only for a present-but-unknown value.
 */
public class InvalidDeckException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates an invalid-deck exception (the offending value is never echoed into the response). */
    public InvalidDeckException() {
        super("Invalid planning poker deck");
    }
}
