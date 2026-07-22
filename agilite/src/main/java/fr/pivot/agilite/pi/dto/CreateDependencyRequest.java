package fr.pivot.agilite.pi.dto;

import fr.pivot.agilite.pi.PiDependencyStatus;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for creating a dependency between two Program Board tickets (US50.3.2).
 *
 * @param fromTicketId the dependency's tail
 * @param toTicketId   the dependency's head
 * @param status       initial status, defaults to {@link PiDependencyStatus#OK} when omitted
 * @param note         an optional free-text note
 */
public record CreateDependencyRequest(
        @NotNull(message = "INVALID_TICKET")
        UUID fromTicketId,
        @NotNull(message = "INVALID_TICKET")
        UUID toTicketId,
        PiDependencyStatus status,
        String note) {
}
