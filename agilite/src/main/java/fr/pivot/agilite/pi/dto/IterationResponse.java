package fr.pivot.agilite.pi.dto;

import fr.pivot.agilite.pi.PiIteration;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Response payload representing a single PI iteration (US50.1.1).
 *
 * @param id        unique identifier of the iteration
 * @param number    1-based position within the cycle
 * @param label     display label, e.g. {@code "IT1"} or {@code "IP Sprint"}
 * @param startDate iteration start date
 * @param endDate   iteration end date
 */
public record IterationResponse(UUID id, int number, String label, LocalDate startDate, LocalDate endDate) {

    /**
     * Factory method that creates an {@link IterationResponse} from a {@link PiIteration} entity.
     *
     * @param iteration the iteration entity
     * @return a populated response record
     */
    public static IterationResponse from(final PiIteration iteration) {
        return new IterationResponse(
                iteration.getId(),
                iteration.getNumber(),
                iteration.getLabel(),
                iteration.getStartDate(),
                iteration.getEndDate());
    }
}
