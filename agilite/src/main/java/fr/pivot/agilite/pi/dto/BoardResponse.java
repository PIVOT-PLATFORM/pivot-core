package fr.pivot.agilite.pi.dto;

import java.util.List;
import java.util.UUID;

/**
 * Aggregated single-payload response for a cycle's Program Board (US50.3.1) — one request
 * returns everything the board needs to render: iterations, Train teams, tickets, and
 * dependencies (US50.3.2).
 *
 * @param cycleId      the cycle this board belongs to
 * @param iterations   the cycle's iterations, sorted by number (the "Unplanned" column and the
 *                      Train row are frontend-only concepts, not represented here)
 * @param teams        the cycle's Train teams, sorted by order
 * @param tickets      the cycle's tickets, sorted by order within each cell
 * @param dependencies the cycle's dependencies
 */
public record BoardResponse(
        UUID cycleId,
        List<IterationResponse> iterations,
        List<PiCycleTeamResponse> teams,
        List<TicketResponse> tickets,
        List<DependencyResponse> dependencies) {
}
