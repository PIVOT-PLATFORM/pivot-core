package fr.pivot.agilite.capacity.dto;

/**
 * Request body for adjusting an event roster member's own fields (US11.2.1). Both fields
 * optional — {@code null} means "leave unchanged".
 *
 * @param excluded            new exclusion flag, or {@code null}
 * @param availabilityPercent new availability percentage, or {@code null} — validated in {@code
 *                             [10, 100]} at the service layer ({@code INVALID_AVAILABILITY}), a
 *                             bean-validation range annotation cannot express "optional but
 *                             bounded when present without treating 0 as absent"
 */
public record UpdateMemberRequest(Boolean excluded, Integer availabilityPercent) {
}
