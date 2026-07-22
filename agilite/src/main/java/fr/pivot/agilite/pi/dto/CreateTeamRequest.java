package fr.pivot.agilite.pi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for manually adding a Train team to a PI cycle (US50.1.1).
 *
 * @param name  the team's display name (1-120 chars)
 * @param color an optional caller-supplied display color (hex); server-assigned from the fixed
 *              palette when omitted, since {@code public.teams} has no color to copy from for the
 *              import endpoint either — see {@code PiCycleTeamColors}
 */
public record CreateTeamRequest(
        @NotBlank(message = "INVALID_NAME")
        @Size(min = 1, max = 120, message = "INVALID_NAME")
        String name,
        String color) {
}
