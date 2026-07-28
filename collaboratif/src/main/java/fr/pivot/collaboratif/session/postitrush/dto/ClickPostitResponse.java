package fr.pivot.collaboratif.session.postitrush.dto;

/**
 * Response body for a successful {@code POST .../postit-rush/click} (US47.2.1) — the clicking
 * participant's own updated state only, computed server-side.
 *
 * @param pointsAwarded the points just awarded for this hit ({@code basePoints × multiplier})
 * @param multiplier    the combo multiplier applied to this hit
 * @param score         the participant's updated cumulative score this round
 * @param currentCombo  the participant's updated combo streak
 * @param hits          the participant's updated total hit count this round
 */
public record ClickPostitResponse(int pointsAwarded, int multiplier, int score, int currentCombo, int hits) {
}
