package fr.pivot.agilite.capacity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for adding or updating a {@link fr.pivot.agilite.capacity.CapacityEventMember}
 * (F11.2).
 *
 * <p>{@code quotite}/{@code focusFactor} range checks are domain rules enforced in the service
 * (not bean validation), so the specific {@code INVALID_QUOTITE}/{@code FOCUS_OUT_OF_RANGE} codes
 * can be returned. {@code teamMemberRef}/{@code role}/{@code focusFactor}/{@code locality}/{@code
 * excluded}/{@code position} are all optional.
 *
 * @param teamMemberRef the linked {@code public.team_members.id}, or {@code null} for a free-text
 *                      (non-roster) member
 * @param name          the member's display name (roster snapshot)
 * @param role          the member's role (roster snapshot), or {@code null}
 * @param quotite       the full-time-equivalent quotity, must be in {@code (0, 1]}
 * @param focusFactor   the per-member focus factor override, or {@code null} to leave unset
 * @param locality      the member's locality, or {@code null}
 * @param excluded      whether to exclude this member from the event's capacity computation, or
 *                      {@code null} to default to {@code false}
 * @param position      the display order within the event, or {@code null} to default to
 *                      {@code 0}
 */
public record CapacityMemberRequest(
        Long teamMemberRef,
        @NotBlank(message = "INVALID_NAME")
        String name,
        String role,
        @NotNull(message = "INVALID_QUOTITE")
        Double quotite,
        Double focusFactor,
        String locality,
        Boolean excluded,
        Integer position) {
}
