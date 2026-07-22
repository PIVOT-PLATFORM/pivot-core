package fr.pivot.agilite.pi.dto;

import java.util.List;

/**
 * Response payload for a Train team import request (US50.1.1).
 *
 * @param importedCount the number of teams actually imported (duplicates/inaccessible/cross-tenant
 *                       ids silently skipped, see AC)
 * @param teams         the newly imported Train teams
 */
public record ImportTeamsResponse(int importedCount, List<PiCycleTeamResponse> teams) {
}
