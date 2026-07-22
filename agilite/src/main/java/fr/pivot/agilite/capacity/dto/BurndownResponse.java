package fr.pivot.agilite.capacity.dto;

import java.util.List;

/**
 * Response payload for a {@code SPRINT} event's burndown chart (US11.4.2).
 *
 * @param ideal   the linear ideal curve, one point per working day; empty if {@code
 *                committedPoints} is not set
 * @param actual  the recorded daily entries, in date order
 * @param atRisk  {@code true} if the actual curve has been above ideal for 2+ consecutive days
 * @param stale   {@code true} if the event is in progress and no entry was recorded in the last
 *                3 calendar days (or ever)
 */
public record BurndownResponse(
        List<BurndownPointResponse> ideal, List<BurndownPointResponse> actual, boolean atRisk, boolean stale) {
}
