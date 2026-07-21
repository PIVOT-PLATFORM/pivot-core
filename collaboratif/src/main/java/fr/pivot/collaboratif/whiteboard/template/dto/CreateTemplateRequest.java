package fr.pivot.collaboratif.whiteboard.template.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body of {@code POST /whiteboard/templates} — creates a personal template (US08.13.2).
 *
 * @param name        display name (1–100 chars)
 * @param description optional short description (up to 500 chars), or {@code null}
 * @param fromBoardId optional source board whose live content is captured into the template; when
 *                    {@code null} the template starts empty. The caller must **own** that board —
 *                    being a shared co-editor is not enough, since capturing a board copies its
 *                    entire content into an object the copier alone then controls
 */
public record CreateTemplateRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        UUID fromBoardId) {
}
