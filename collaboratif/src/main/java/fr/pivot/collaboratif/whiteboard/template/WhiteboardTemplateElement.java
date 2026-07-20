package fr.pivot.collaboratif.whiteboard.template;

import fr.pivot.collaboratif.whiteboard.canvas.TemplateElementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * JPA entity representing a single seed element of a whiteboard template (US08.4.1, re-platformed
 * onto the live board model by EN08.x).
 *
 * <p>Global template rows are exclusively seeded via Flyway ({@code V1__schema_init.sql} plus
 * additive migrations, e.g. {@code V7__whiteboard_template_replatform.sql}); there is no
 * user-facing authoring endpoint for <em>global</em> templates in the Socle (see the US "Hors
 * périmètre" section). {@link #elementType} selects which live-model entity this element
 * materializes into when a board is initialized from its template (see
 * {@code WhiteboardTemplateService#initializeBoard}): {@code FRAME}/{@code CARD}/
 * {@code CONNECTION}/{@code FIELD}/{@code FIELD_VALUE} become a real {@code Frame}/{@code Card}/
 * {@code CardConnection}/{@code BoardField}/{@code CardFieldValue} row respectively — the exact
 * rows the live board renders from via {@code board:state}. This supersedes the original design
 * (SHAPE/TEXT/IMAGE materializing only a legacy {@code canvas_event} {@code DRAW} row, a channel
 * the routed board surface never reads).
 *
 * <p>{@link #localKey} is a template-scoped identifier (present on {@code FRAME}/{@code CARD}/
 * {@code FIELD} elements) that {@code CONNECTION} (via {@code fromKey}/{@code toKey} in its
 * payload) and {@code FIELD_VALUE} (via {@code cardKey}/{@code fieldKey}) elements reference to
 * point at another element of the same template — resolved to the real generated UUID at
 * materialization time, since a template element has no UUID identity of its own until then.
 *
 * <p>US08.2.4 ("save as template") introduces application-created rows for tenant-owned
 * templates, snapshotting a board's current Card/Frame/CardConnection/BoardField content (see
 * {@code WhiteboardTemplateService#createFromBoard}) using the same element vocabulary and
 * validated against the same {@code TemplateElementValidator} schema as global templates.
 *
 * <p>{@code templateId} is a plain column rather than a JPA association, mirroring the
 * convention used by {@code CanvasEvent#boardId} elsewhere in this module.
 */
@Entity
@Table(name = "whiteboard_template_element", schema = "collaboratif")
public class WhiteboardTemplateElement {

    /** UUID generated server-side (or by the database default in seed data). */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Template this element belongs to. */
    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    /** Whitelisted element kind — see {@link TemplateElementType}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "element_type", nullable = false, length = 20, updatable = false)
    private TemplateElementType elementType;

    /**
     * Template-scoped identifier used by {@code CONNECTION}/{@code FIELD_VALUE} elements of the
     * same template to reference this element by key rather than by (not-yet-assigned) UUID.
     * {@code null} for elements nothing references (a {@code CONNECTION} typically has no
     * {@code localKey} of its own).
     */
    @Column(name = "local_key", length = 64, updatable = false)
    private String localKey;

    /**
     * JSON payload describing the element's geometry and type-specific fields, stored as
     * JSONB. See {@code TemplateElementValidator} for the exact schema per {@link #elementType}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String payload;

    /**
     * Ordering position within the template. Materialization processes {@code FRAME}/
     * {@code CARD}/{@code FIELD} elements (in this order key) before {@code CONNECTION}/
     * {@code FIELD_VALUE} elements, so this is a display/authoring order, not a strict dependency
     * order — see {@code WhiteboardTemplateService#initializeBoard}.
     */
    @Column(name = "display_order", nullable = false, updatable = false)
    private int displayOrder;

    /** No-arg constructor required by JPA. */
    protected WhiteboardTemplateElement() {
    }

    /**
     * Creates a new template element.
     *
     * @param id           server-generated UUID
     * @param templateId   owning template's UUID
     * @param elementType  whitelisted element kind
     * @param localKey     template-scoped reference key, or {@code null} if nothing references
     *                     this element
     * @param payload      JSON payload string, validated against {@code TemplateElementValidator}
     * @param displayOrder ordering position within the template
     */
    public WhiteboardTemplateElement(
            final UUID id,
            final UUID templateId,
            final TemplateElementType elementType,
            final String localKey,
            final String payload,
            final int displayOrder) {
        this.id = id;
        this.templateId = templateId;
        this.elementType = elementType;
        this.localKey = localKey;
        this.payload = payload;
        this.displayOrder = displayOrder;
    }

    /**
     * Returns the element's unique identifier.
     *
     * @return the UUID
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the owning template's UUID.
     *
     * @return the template UUID
     */
    public UUID getTemplateId() {
        return templateId;
    }

    /**
     * Returns the whitelisted element kind.
     *
     * @return the element type
     */
    public TemplateElementType getElementType() {
        return elementType;
    }

    /**
     * Returns the template-scoped reference key.
     *
     * @return the local key, or {@code null} if this element is not referenced by key
     */
    public String getLocalKey() {
        return localKey;
    }

    /**
     * Returns the JSON payload string.
     *
     * @return the payload
     */
    public String getPayload() {
        return payload;
    }

    /**
     * Returns the display order within the template.
     *
     * @return the display order
     */
    public int getDisplayOrder() {
        return displayOrder;
    }
}
