package fr.pivot.collaboratif.exception;

/**
 * Thrown when a whiteboard template element payload violates the strict JSON schema whitelist
 * enforced by {@code TemplateElementValidator} (one closed schema per {@code
 * TemplateElementType} — frame/card/connection/field/field value — closed field set, bounded
 * values). Also used, unchanged, by {@code WhiteboardTemplateService#initializeBoard} when a
 * {@code CONNECTION}/{@code FIELD_VALUE} element references an unresolvable {@code localKey}.
 *
 * <p>The only caller-supplied input on the template <em>initialization</em> path
 * ({@code WhiteboardTemplateService#initializeBoard}) is {@code templateId} — never the
 * element content itself. That method only ever resolves Flyway-seeded global templates
 * ({@code WhiteboardTemplateService#resolveGlobalTemplate}, {@code tenant_id IS NULL}), so a
 * failure there signals an internal invariant violation (seed data drifted from the schema),
 * not a client input error, and is mapped to HTTP 500 by {@code CollaboratifExceptionHandler}
 * with the underlying detail logged server-side only. Note that tenant-owned templates created
 * via "save as template" ({@code WhiteboardTemplateService#createFromBoard}) store unvalidated
 * content and are not currently reachable by {@code initializeBoard} — a future endpoint
 * resolving them through this validator must not assume a 500 remains the correct response for
 * caller-influenced content.
 */
public class InvalidCanvasElementException extends RuntimeException {

    /**
     * Creates an invalid-canvas-element exception with a diagnostic message.
     *
     * @param message description of the schema violation, for server-side logging only
     */
    public InvalidCanvasElementException(final String message) {
        super(message);
    }
}
