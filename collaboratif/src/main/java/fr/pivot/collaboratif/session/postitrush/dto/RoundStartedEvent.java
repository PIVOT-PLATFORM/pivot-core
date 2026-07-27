package fr.pivot.collaboratif.session.postitrush.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * STOMP broadcast {@code ROUND_STARTED} (US47.2.1) — server-authoritative round clock; the client
 * countdown built from {@code durationSeconds}/{@code startedAt} is purely visual.
 *
 * @param type            discriminator, always {@code "ROUND_STARTED"}
 * @param roundId         the new round's id
 * @param durationSeconds server-authoritative duration (default 90)
 * @param startedAt       server-authoritative start instant
 */
public record RoundStartedEvent(String type, UUID roundId, int durationSeconds, Instant startedAt) {

    /**
     * Creates the event with its fixed discriminator.
     *
     * @param roundId         the new round's id
     * @param durationSeconds server-authoritative duration
     * @param startedAt       server-authoritative start instant
     */
    public RoundStartedEvent(final UUID roundId, final int durationSeconds, final Instant startedAt) {
        this("ROUND_STARTED", roundId, durationSeconds, startedAt);
    }
}
