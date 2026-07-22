package fr.pivot.agilite.pi.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for importing PIVOT teams into a PI cycle as Train teams (US50.1.1).
 *
 * @param teamIds the {@code public.teams.id} values to import (1-30) — each is individually
 *                validated (membership, tenant, duplicate) at the service layer; an invalid
 *                individual id is silently skipped rather than failing the whole batch (see AC)
 */
public record ImportTeamsRequest(
        @NotEmpty(message = "EMPTY_TEAM_IDS")
        @Size(max = 30, message = "TOO_MANY_TEAM_IDS")
        List<Long> teamIds) {
}
