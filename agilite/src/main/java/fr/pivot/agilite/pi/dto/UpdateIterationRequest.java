package fr.pivot.agilite.pi.dto;

import java.time.LocalDate;

/**
 * Request body for adjusting an already-generated PI iteration (US50.1.1). All fields optional —
 * only non-{@code null} values are applied.
 */
public record UpdateIterationRequest(String label, LocalDate startDate, LocalDate endDate) {
}
