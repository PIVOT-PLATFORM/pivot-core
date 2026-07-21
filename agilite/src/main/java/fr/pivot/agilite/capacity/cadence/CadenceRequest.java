package fr.pivot.agilite.capacity.cadence;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /capacity/events/{piId}/cadence} — F11.5 PI/SAFe cadence, the
 * "auto" side of the period auto|manual distinction: the caller supplies a cadence spec and this
 * slice lays out the PI's sprints for them. "Manuel" is simply not calling this endpoint and
 * creating each {@code SPRINT} event individually via the existing {@code POST /capacity/events}
 * (F11.1) — no separate manual DTO exists.
 *
 * <p>Exactly one of {@code sprintLengthDays}/{@code sprintLengthWeeks} must be supplied — this is
 * not expressible via bean validation and is checked by {@link CadencePlanner}, which throws
 * {@code CapacityValidationException} with code {@code INVALID_CADENCE} otherwise. The IP
 * (Innovation &amp; Planning) sprint appended when {@code includeIpSprint} is {@code true} reuses
 * the same length as the regular sprints.
 *
 * @param sprintLengthDays  the length of each generated sprint, in calendar days, or {@code null}
 *                          if {@code sprintLengthWeeks} is supplied instead
 * @param sprintLengthWeeks the length of each generated sprint, in whole weeks, or {@code null}
 *                          if {@code sprintLengthDays} is supplied instead
 * @param sprintCount       how many regular sprints to generate, at least {@code 1}
 * @param includeIpSprint   whether to append a trailing SAFe Innovation &amp; Planning sprint,
 *                          marked via {@code CapacityEvent#isIpSprint()}
 * @param namePrefix        the display-name prefix for generated sprints (e.g. {@code "Sprint"}
 *                          produces {@code "Sprint 1"}, {@code "Sprint 2"}, ...); defaults to
 *                          {@code "Sprint"} when {@code null} or blank
 */
public record CadenceRequest(
        Integer sprintLengthDays,
        Integer sprintLengthWeeks,
        @NotNull(message = "INVALID_CADENCE")
        @Min(value = 1, message = "INVALID_CADENCE")
        Integer sprintCount,
        boolean includeIpSprint,
        String namePrefix) {
}
