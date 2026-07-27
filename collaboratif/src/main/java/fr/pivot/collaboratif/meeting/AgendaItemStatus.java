package fr.pivot.collaboratif.meeting;

/**
 * Animation status of a single {@link AgendaItem} within its owning {@link Meeting} (US12.2.1).
 *
 * <p>Every item starts {@link #PENDING} (the value materialized by US12.1.1's creation flow, via
 * the {@code item_status} column's {@code DEFAULT 'PENDING'}), becomes {@link #CURRENT} exactly
 * once (when the animator starts the meeting or advances to it), then {@link #DONE} exactly once
 * (when the animator advances past it or ends the meeting) — a strictly monotonic
 * {@code PENDING → CURRENT → DONE} progression, never reversed.
 */
public enum AgendaItemStatus {

    /** Not yet reached — the default status for every item at meeting creation. */
    PENDING,

    /** The item currently being animated — at most one per meeting. */
    CURRENT,

    /** Concluded — either advanced past, or the meeting ended while it was current. */
    DONE
}
