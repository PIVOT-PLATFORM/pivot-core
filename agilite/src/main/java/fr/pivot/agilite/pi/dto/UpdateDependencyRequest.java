package fr.pivot.agilite.pi.dto;

import fr.pivot.agilite.pi.PiDependencyStatus;

/**
 * Request body for updating a dependency's status/note (US50.3.2). {@code fromTicketId}/{@code
 * toTicketId} are never modifiable — delete and recreate if the link itself must change (see AC).
 * Fields optional — only non-{@code null} values are applied.
 */
public record UpdateDependencyRequest(PiDependencyStatus status, String note) {
}
