package fr.pivot.collaboratif.whiteboard.canvas;

/**
 * Whitelisted kinds of seed content a whiteboard template element can materialize into on the
 * live board model (EN08.x re-platform).
 *
 * <p>Supersedes the retired {@code CanvasElementType} (SHAPE/TEXT/IMAGE), which only ever
 * produced legacy {@code canvas_event} {@code DRAW} rows — a channel the routed board surface
 * ({@code structured-canvas}, EN08.4) never reads (see {@code WhiteboardTemplateService}
 * Javadoc). Each of these five kinds instead materializes a real, durable row in the same
 * table the live board renders from via {@code board:state}: {@link #FRAME} → {@link Frame},
 * {@link #CARD} → {@link Card}, {@link #CONNECTION} → {@link CardConnection}, {@link #FIELD} →
 * {@link BoardField}, {@link #FIELD_VALUE} → {@link CardFieldValue}.
 */
public enum TemplateElementType {
    /** A titled container box — see {@link Frame}. */
    FRAME,
    /** A board object (sticky note, label, shape) — see {@link Card}. */
    CARD,
    /** A connector between two {@link #CARD} elements of the same template — see {@link CardConnection}. */
    CONNECTION,
    /** A board-level custom field definition — see {@link BoardField}. */
    FIELD,
    /** A card's value for a {@link #FIELD} element of the same template — see {@link CardFieldValue}. */
    FIELD_VALUE
}
