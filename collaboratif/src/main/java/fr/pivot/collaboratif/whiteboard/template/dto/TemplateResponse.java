package fr.pivot.collaboratif.whiteboard.template.dto;

import fr.pivot.collaboratif.whiteboard.template.WhiteboardTemplate;

import java.util.UUID;

/**
 * Response payload representing a whiteboard template available in the gallery.
 *
 * <p>{@code code} is the stable machine-readable identifier the frontend uses as an i18n
 * key prefix ({@code whiteboard.template.<code>.name} / {@code .description}) to localize
 * {@code name} and {@code description}; the values returned here are the French defaults
 * stored in the database, used as a fallback.
 *
 * @param id           unique identifier of the template
 * @param code         stable machine-readable code (e.g. {@code "BRAINSTORM"})
 * @param name         default display name
 * @param description  default short description, or {@code null}
 * @param thumbnailUrl URL of the gallery preview image, or {@code null}
 * @param personal     {@code true} when the caller owns this template and may therefore edit or
 *                     delete it; {@code false} for the global templates shipped with the product,
 *                     which everyone may use and nobody may change (US08.13.2)
 */
public record TemplateResponse(
        UUID id,
        String code,
        String name,
        String description,
        String thumbnailUrl,
        boolean personal) {

    /**
     * Creates a {@link TemplateResponse} from a {@link WhiteboardTemplate} entity.
     *
     * @param template the template entity
     * @return a populated response record
     */
    public static TemplateResponse from(final WhiteboardTemplate template) {
        return from(template, false);
    }

    /**
     * Creates a {@link TemplateResponse}, flagging whether the caller owns the template.
     *
     * @param template the template entity
     * @param personal {@code true} if the calling user owns it
     * @return a populated response record
     */
    public static TemplateResponse from(final WhiteboardTemplate template, final boolean personal) {
        return new TemplateResponse(
                template.getId(),
                template.getCode(),
                template.getName(),
                template.getDescription(),
                template.getThumbnailUrl(),
                personal);
    }
}
