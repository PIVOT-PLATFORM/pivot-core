package fr.pivot.collaboratif.session.postitrush.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * STOMP broadcast {@code POSTIT_SPAWNED} (US47.2.1) — server-generated position/color/timing;
 * the client never decides any of these fields, only renders them.
 *
 * @param type       discriminator, always {@code "POSTIT_SPAWNED"}
 * @param postitId   the spawned post-it's id
 * @param x          percentage x-coordinate (0-100)
 * @param y          percentage y-coordinate (0-100)
 * @param colorKey   non-color visual identity key (paired with shape/label client-side, WCAG 1.4.1)
 * @param spawnedAt  server-authoritative spawn instant
 * @param lifespanMs random lifespan in milliseconds (1200-2500)
 */
public record PostitSpawnedEvent(
        String type, UUID postitId, double x, double y, String colorKey, Instant spawnedAt, int lifespanMs) {

    /**
     * Creates the event with its fixed discriminator.
     *
     * @param postitId   the spawned post-it's id
     * @param x          percentage x-coordinate
     * @param y          percentage y-coordinate
     * @param colorKey   non-color visual identity key
     * @param spawnedAt  server-authoritative spawn instant
     * @param lifespanMs random lifespan in milliseconds
     */
    public PostitSpawnedEvent(
            final UUID postitId,
            final double x,
            final double y,
            final String colorKey,
            final Instant spawnedAt,
            final int lifespanMs) {
        this("POSTIT_SPAWNED", postitId, x, y, colorKey, spawnedAt, lifespanMs);
    }
}
