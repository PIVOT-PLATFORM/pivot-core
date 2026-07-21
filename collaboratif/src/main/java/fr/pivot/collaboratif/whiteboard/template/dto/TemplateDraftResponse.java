package fr.pivot.collaboratif.whiteboard.template.dto;

import java.util.UUID;

/**
 * Response of {@code POST /whiteboard/templates/{templateId}/edit-content} — the board to open in
 * the canvas to edit a template's content (US08.13.2).
 *
 * @param boardId  the draft board's UUID
 * @param created  {@code true} when this call opened a new draft, {@code false} when it handed back
 *                 one that was already open. Both answer 200 — the flag exists so the client can
 *                 tell the user their earlier, unsaved work was recovered rather than silently
 *                 presenting it as a fresh draft
 */
public record TemplateDraftResponse(UUID boardId, boolean created) {
}
