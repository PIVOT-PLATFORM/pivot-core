package fr.pivot.collaboratif.session.postitrush.dto;

import java.util.List;

/**
 * Response body for {@code GET .../postit-rush/results} (US47.2.1) — the final standings of the
 * most recently completed round, visible to every participant.
 *
 * @param standings the ranked final standings
 */
public record PostitRushResultsDto(List<PostitRushStandingEntry> standings) {
}
