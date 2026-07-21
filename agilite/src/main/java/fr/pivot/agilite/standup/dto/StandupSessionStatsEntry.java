package fr.pivot.agilite.standup.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A single {@link fr.pivot.agilite.standup.StandupSessionStatus#DONE} session's stats entry
 * (US10.3.1).
 *
 * @param id              the session's id
 * @param name            the session's name
 * @param startedAt       when the session was started
 * @param durationSeconds {@code endedAt - startedAt}, in seconds
 */
public record StandupSessionStatsEntry(UUID id, String name, Instant startedAt, long durationSeconds) {
}
