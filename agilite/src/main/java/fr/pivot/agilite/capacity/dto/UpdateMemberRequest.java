package fr.pivot.agilite.capacity.dto;

/**
 * Request body for adjusting an event roster member's own fields (US11.2.1/US11.6.2). All fields
 * optional — {@code null} means "leave unchanged".
 *
 * @param excluded            new exclusion flag, or {@code null}
 * @param availabilityPercent new availability percentage, or {@code null} — validated in {@code
 *                             [10, 100]} at the service layer ({@code INVALID_AVAILABILITY}), a
 *                             bean-validation range annotation cannot express "optional but
 *                             bounded when present without treating 0 as absent"
 * @param focusFactorPercent  new per-member focus-factor override in {@code [10, 100]}
 *                            (US11.6.2), or {@code null} to leave unchanged
 */
public record UpdateMemberRequest(Boolean excluded, Integer availabilityPercent, Integer focusFactorPercent) {
}
