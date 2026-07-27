package fr.pivot.collaboratif.meeting;

/**
 * Lifecycle status of a {@link Meeting}.
 *
 * <p>Only {@link #DRAFT} exists so far — the status a meeting is created in by US12.1.1 (this
 * US only covers creation, see the pivot-docs "Hors périmètre" section). {@code PRE_RESERVED}/
 * {@code CONFIRMED} (US12.4.1's roadmap booking flow) and any animation-lifecycle status
 * (US12.2.1) are deliberately not added here yet — the Architect Agent still owns whether they
 * extend this same enum or a separate one, per EN12.1's own implementation note. Adding a value
 * later is a purely additive change (column is a plain {@code VARCHAR}, the {@code
 * chk_meeting_status} constraint is widened via a new Flyway migration).
 */
public enum MeetingStatus {

    /** A meeting created but not yet started/pre-reserved/confirmed. */
    DRAFT
}
