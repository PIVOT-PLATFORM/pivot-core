package fr.pivot.agilite.pi.dto;

import fr.pivot.agilite.pi.PiCycle;
import fr.pivot.agilite.pi.PiCycleStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response payload representing a full PI cycle, including its iterations and Train teams
 * (US50.1.1).
 *
 * @param id             unique identifier of the cycle
 * @param name           cycle name
 * @param artName        Agile Release Train name, or {@code null}
 * @param status         lifecycle status
 * @param startDate      cycle start date
 * @param endDate        cycle end date
 * @param eventDay1      reserved event day (US50.2.1), or {@code null}
 * @param eventDay2      reserved event day (US50.2.1), or {@code null}
 * @param eventLocation  reserved event location (US50.2.1), or {@code null}
 * @param createdBy      creating user's {@code public.users.id}
 * @param createdAt      creation timestamp
 * @param updatedAt      last-update timestamp
 * @param iterations     the cycle's iterations, sorted by number
 * @param teams          the cycle's Train teams, sorted by order
 */
public record CycleResponse(
        UUID id,
        String name,
        String artName,
        PiCycleStatus status,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate eventDay1,
        LocalDate eventDay2,
        String eventLocation,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt,
        List<IterationResponse> iterations,
        List<PiCycleTeamResponse> teams) {

    /**
     * Factory method that creates a {@link CycleResponse} from a {@link PiCycle} entity.
     *
     * @param cycle the cycle entity
     * @return a populated response record
     */
    public static CycleResponse from(final PiCycle cycle) {
        return new CycleResponse(
                cycle.getId(),
                cycle.getName(),
                cycle.getArtName(),
                cycle.getStatus(),
                cycle.getStartDate(),
                cycle.getEndDate(),
                cycle.getEventDay1(),
                cycle.getEventDay2(),
                cycle.getEventLocation(),
                cycle.getCreatedBy(),
                cycle.getCreatedAt(),
                cycle.getUpdatedAt(),
                cycle.getIterations().stream().map(IterationResponse::from).toList(),
                cycle.getTeams().stream().map(PiCycleTeamResponse::from).toList());
    }
}
