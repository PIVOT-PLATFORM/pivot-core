package fr.pivot.agilite.standup.dto;

/**
 * A single participant's aggregated speaking stats over a stats period (US10.3.1).
 *
 * @param name                 the participant's denormalized display name
 * @param sessionCount         number of sessions in the period where this participant spoke
 * @param totalSpeakingSeconds total speaking time across those sessions, in seconds ({@code
 *                             SKIPPED} turns always contribute {@code 0})
 */
public record StandupParticipantStatsEntry(String name, long sessionCount, long totalSpeakingSeconds) {
}
