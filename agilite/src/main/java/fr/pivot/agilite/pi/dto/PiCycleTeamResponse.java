package fr.pivot.agilite.pi.dto;

import fr.pivot.agilite.pi.PiCycleTeam;

import java.util.UUID;

/**
 * Response payload representing a PI cycle's Train team (US50.1.1).
 *
 * @param id           unique identifier of the Train team row
 * @param name         display name
 * @param color        display color (hex)
 * @param order        display order among the cycle's teams
 * @param sourceTeamId the source {@code public.teams.id}, or {@code null} for a manual entry
 */
public record PiCycleTeamResponse(UUID id, String name, String color, int order, Long sourceTeamId) {

    /**
     * Factory method that creates a {@link PiCycleTeamResponse} from a {@link PiCycleTeam} entity.
     *
     * @param team the Train team entity
     * @return a populated response record
     */
    public static PiCycleTeamResponse from(final PiCycleTeam team) {
        return new PiCycleTeamResponse(
                team.getId(), team.getName(), team.getColor(), team.getTeamOrder(), team.getSourceTeamId());
    }
}
