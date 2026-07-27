package fr.pivot.collaboratif.meeting;

/**
 * Category of a single {@link AgendaItem} (US12.1.1 AC2).
 *
 * <p>Stored as ASCII in the database and over the wire — {@code DECISION}, never the accented
 * French {@code DÉCISION}; the accented label is an i18n concern of the UI only (pivot-docs
 * "Modèle" note).
 */
public enum AgendaItemType {

    /** An informational point — no discussion or decision expected. */
    INFO,

    /** A point open for discussion among participants. */
    DISCUSSION,

    /** A point where a decision is expected to be made. */
    DECISION
}
