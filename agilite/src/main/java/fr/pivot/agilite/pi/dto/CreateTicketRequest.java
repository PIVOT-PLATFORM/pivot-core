package fr.pivot.agilite.pi.dto;

import fr.pivot.agilite.pi.PiTicketType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for creating a new Program Board ticket (US50.3.1).
 *
 * @param type        the ticket type
 * @param title       the ticket title (1-300 chars)
 * @param description an optional free-text description (max 3000 chars, validated at the
 *                     service layer since a client can legitimately omit it)
 * @param teamId      the target Train team, or {@code null} for the dedicated Train row
 * @param iterationId the target iteration, or {@code null} for the "Unplanned" column
 */
public record CreateTicketRequest(
        @NotNull(message = "INVALID_TICKET_TYPE")
        PiTicketType type,
        @NotBlank(message = "INVALID_TITLE")
        @Size(min = 1, max = 300, message = "INVALID_TITLE")
        String title,
        String description,
        UUID teamId,
        UUID iterationId) {
}
