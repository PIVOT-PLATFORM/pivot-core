package fr.pivot.agilite.pi.dto;

import fr.pivot.agilite.pi.PiCycle;
import fr.pivot.agilite.pi.PiCycleStatus;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Lightweight response payload for a PI cycle in the accessible-cycles list (US50.1.1) — carries
 * iteration/team counts rather than the full nested lists {@link CycleResponse} exposes, per the
 * AC ("chacun avec le compte d'itérations/équipes").
 *
 * @param id             unique identifier of the cycle
 * @param name           cycle name
 * @param artName        Agile Release Train name, or {@code null}
 * @param status         lifecycle status
 * @param startDate      cycle start date
 * @param endDate        cycle end date
 * @param iterationCount number of iterations
 * @param teamCount      number of Train teams
 */
public record CycleSummaryResponse(
        UUID id,
        String name,
        String artName,
        PiCycleStatus status,
        LocalDate startDate,
        LocalDate endDate,
        int iterationCount,
        int teamCount) {

    /**
     * Factory method that creates a {@link CycleSummaryResponse} from a {@link PiCycle} entity.
     *
     * @param cycle the cycle entity
     * @return a populated response record
     */
    public static CycleSummaryResponse from(final PiCycle cycle) {
        return new CycleSummaryResponse(
                cycle.getId(),
                cycle.getName(),
                cycle.getArtName(),
                cycle.getStatus(),
                cycle.getStartDate(),
                cycle.getEndDate(),
                cycle.getIterations().size(),
                cycle.getTeams().size());
    }
}
