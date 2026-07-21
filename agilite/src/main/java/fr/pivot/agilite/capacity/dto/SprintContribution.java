package fr.pivot.agilite.capacity.dto;

/**
 * One sprint's contribution to its parent PI's consolidated capacity, consumed by {@code
 * fr.pivot.agilite.capacity.calc.CapacityCalculator#consolidatePi} (E11 — capacity planning).
 *
 * @param sprintCapacity the sprint's own {@link EventCapacityResult}
 * @param ipSprint       whether this sprint is a SAFe Innovation &amp; Planning sprint — excluded
 *                       from the PI total when consolidation runs with {@code applySafeIpExclusion
 *                       = true}
 */
public record SprintContribution(EventCapacityResult sprintCapacity, boolean ipSprint) {
}
