package fr.pivot.collaboratif.session.postitrush.dto;

import java.util.UUID;

/**
 * STOMP broadcast {@code ROUND_ENDED} (US47.2.1) — the server-authoritative timer hit zero;
 * clients should fetch {@code GET .../results} for the final standings.
 *
 * @param type    discriminator, always {@code "ROUND_ENDED"}
 * @param roundId the ended round's id
 */
public record RoundEndedEvent(String type, UUID roundId) {

    /**
     * Creates the event with its fixed discriminator.
     *
     * @param roundId the ended round's id
     */
    public RoundEndedEvent(final UUID roundId) {
        this("ROUND_ENDED", roundId);
    }
}
