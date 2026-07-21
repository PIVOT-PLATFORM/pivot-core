package fr.pivot.collaboratif.whiteboard.template.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /whiteboard/templates/{templateId}} — renames or re-describes a personal
 * template (US08.13.2).
 *
 * <p>Visibility and sharing are deliberately absent: they belong to US08.13.5, which extends this
 * record rather than reinterpreting it.
 *
 * @param name        the new display name (1–100 chars)
 * @param description the new short description (up to 500 chars), or {@code null} to clear it
 */
public record UpdateTemplateRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description) {
}
