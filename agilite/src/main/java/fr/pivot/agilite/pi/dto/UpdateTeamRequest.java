package fr.pivot.agilite.pi.dto;

/**
 * Request body for updating a PI cycle's Train team (US50.1.1). All fields optional — only
 * non-{@code null} values are applied.
 */
public record UpdateTeamRequest(String name, String color, Integer order) {
}
