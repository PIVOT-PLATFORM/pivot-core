package fr.pivot.collaboratif.meeting.dto;

/**
 * Non-blocking warning surfaced when a meeting's agenda item durations do not sum to its
 * {@code totalDurationMinutes} (US12.1.1 AC3). Computed on the fly at creation time — never
 * persisted (see {@code Meeting}, which has no column for it).
 *
 * @param expectedMinutes the meeting's own {@code totalDurationMinutes}
 * @param sumMinutes      the sum of every agenda item's {@code durationMinutes}
 * @param deltaMinutes    {@code sumMinutes - expectedMinutes} — positive when the agenda
 *                        overruns the planned total, negative when it falls short
 */
public record AgendaDurationMismatch(int expectedMinutes, int sumMinutes, int deltaMinutes) {
}
