package fr.pivot.agilite.standup.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for creating a new standup session (US10.1.1).
 *
 * <p>{@code teamId} is the session's owning team. {@code name} must be 1-100 characters. {@code
 * timePerPersonSeconds} is optional — {@code null} defaults to {@code
 * StandupSession#DEFAULT_TIME_PER_PERSON_SECONDS} (120s) at the service layer; when provided it
 * must fall within [30, 1800]. {@code participantTeamMemberIds} must contain at least one
 * element, in the exact order the caller wants the speaking rotation to follow — the server never
 * randomizes it.
 */
public record CreateStandupSessionRequest(
        @NotNull(message = "INVALID_TEAM")
        Long teamId,
        @NotBlank(message = "INVALID_NAME")
        @Size(min = 1, max = 100, message = "INVALID_NAME")
        String name,
        @Min(value = 30, message = "INVALID_TIME_PER_PERSON")
        @Max(value = 1800, message = "INVALID_TIME_PER_PERSON")
        Integer timePerPersonSeconds,
        @NotEmpty(message = "EMPTY_PARTICIPANTS")
        List<@NotNull Long> participantTeamMemberIds) {
}
