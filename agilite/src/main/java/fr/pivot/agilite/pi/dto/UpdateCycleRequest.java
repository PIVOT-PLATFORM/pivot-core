package fr.pivot.agilite.pi.dto;

import fr.pivot.agilite.pi.PiCycleStatus;

import java.time.LocalDate;

/**
 * Request body for updating a PI cycle's own fields (US50.1.1). All fields optional — only
 * non-{@code null} values are applied. {@code status} transitions freely (no state machine at
 * the socle, see AC).
 */
public record UpdateCycleRequest(
        String name,
        String artName,
        PiCycleStatus status,
        LocalDate eventDay1,
        LocalDate eventDay2,
        String eventLocation) {
}
