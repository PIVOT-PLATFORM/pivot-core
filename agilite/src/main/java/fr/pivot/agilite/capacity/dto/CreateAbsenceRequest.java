package fr.pivot.agilite.capacity.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request body for recording an absence (US11.2.2).
 *
 * <p><strong>RGPD — deliberately declares no {@code motif}/{@code reason} field</strong>
 * (US11.2.2 §Architecture, explicit maintainer decision). A legacy or malicious client sending
 * one anyway has nothing intercepted explicitly for — standard Jackson deserialization simply
 * never maps an unknown JSON property to any field of this record, so it is silently dropped,
 * never persisted, never returned.
 */
public record CreateAbsenceRequest(
        @NotNull(message = "INVALID_DATE_RANGE")
        LocalDate dateDebut,
        @NotNull(message = "INVALID_DATE_RANGE")
        LocalDate dateFin) {
}
