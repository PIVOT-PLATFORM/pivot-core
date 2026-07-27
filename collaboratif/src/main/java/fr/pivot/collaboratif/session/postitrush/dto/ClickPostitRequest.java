package fr.pivot.collaboratif.session.postitrush.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code POST .../postit-rush/click} (US47.2.1).
 *
 * <p>Deliberately carries <strong>only</strong> the clicked post-it's id — never a
 * client-supplied score/points/combo. The server alone validates liveness and computes the
 * awarded points; any such field on an untyped request body would be silently ignored by this
 * record's very shape (there is nowhere to put it).
 *
 * @param postitId the clicked post-it's id
 */
public record ClickPostitRequest(@NotNull(message = "INVALID_POSTIT_ID") UUID postitId) {
}
