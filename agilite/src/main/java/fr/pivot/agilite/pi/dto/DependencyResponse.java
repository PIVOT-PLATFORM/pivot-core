package fr.pivot.agilite.pi.dto;

import fr.pivot.agilite.pi.PiDependency;
import fr.pivot.agilite.pi.PiDependencyStatus;

import java.util.UUID;

/**
 * Response payload representing a dependency between two Program Board tickets (US50.3.2).
 *
 * @param id           unique identifier of the dependency
 * @param fromTicketId the dependency's tail
 * @param toTicketId   the dependency's head
 * @param status       visual/business status
 * @param note         free-text note, or {@code null}
 */
public record DependencyResponse(UUID id, UUID fromTicketId, UUID toTicketId, PiDependencyStatus status, String note) {

    /**
     * Factory method that creates a {@link DependencyResponse} from a {@link PiDependency} entity.
     *
     * @param dependency the dependency entity
     * @return a populated response record
     */
    public static DependencyResponse from(final PiDependency dependency) {
        return new DependencyResponse(
                dependency.getId(),
                dependency.getFromTicketId(),
                dependency.getToTicketId(),
                dependency.getStatus(),
                dependency.getNote());
    }
}
