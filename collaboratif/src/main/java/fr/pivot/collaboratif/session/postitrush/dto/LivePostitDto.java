package fr.pivot.collaboratif.session.postitrush.dto;

import java.util.UUID;

/**
 * A currently-live post-it as returned by the reconnect {@code GET .../postit-rush/state}
 * endpoint (US47.2.1) — the same fields as {@code PostitSpawnedEvent} plus the remaining lifespan
 * computed at read time, so a rejoining client can render exactly what is currently on the board
 * without ever seeing future spawn timing.
 *
 * @param postitId    the post-it's id
 * @param x           percentage x-coordinate (0-100)
 * @param y           percentage y-coordinate (0-100)
 * @param colorKey    non-color visual identity key
 * @param remainingMs milliseconds remaining before this post-it expires, computed at read time
 */
public record LivePostitDto(UUID postitId, double x, double y, String colorKey, long remainingMs) {
}
