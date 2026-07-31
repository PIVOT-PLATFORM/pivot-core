package fr.pivot.collaboratif.exception;

/**
 * HTTP 422 when a confirm/adjust request targets a {@code slotId} that does not belong to the
 * meeting's own {@code proposed_slots} (US12.4.1 "Error — validation d'un créneau invalide").
 *
 * <p>Distinct from a plain 400/409: the request is syntactically valid and the meeting itself is
 * in a valid state to be confirmed, but the specific slot reference is unprocessable — the
 * canonical use of 422 Unprocessable Entity.
 */
public class MeetingSlotInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     */
    public MeetingSlotInvalidException() {
        super("Slot does not belong to this meeting's proposed slots");
    }
}
