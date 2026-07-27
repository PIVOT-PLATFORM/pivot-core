package fr.pivot.collaboratif.meeting;

/**
 * Lifecycle status of a {@link Meeting}.
 *
 * <p>{@link #PRE_RESERVED} and {@link #CONFIRMED} were added by US12.4.1 (roadmap booking flow,
 * E12): a meeting created from a {@code roadmap.event.window.created} event starts life in {@code
 * PRE_RESERVED} — a draft with proposed slots, no invitation sent yet — and moves to {@code
 * CONFIRMED} once the organizer validates a slot (invitation sent, {@code
 * meetops.booking.confirmed} published). This is purely additive on top of {@link #DRAFT} (US12.1.1's
 * only value, the status a manually-created meeting-with-agenda still starts in) — the column stays
 * a plain {@code VARCHAR}, widened via {@code V20__meetops_booking.sql}'s {@code
 * chk_meeting_status} constraint change, so nothing about US12.1.1's own DRAFT flow is affected.
 *
 * <p>No {@code CANCELLED} value: a {@code roadmap.event.window.deleted} on a non-confirmed
 * booking-flow meeting deletes the row outright (cascading its {@code proposed_slots}) rather than
 * flipping a status — see {@code BookingService#handleWindowDeleted}'s Javadoc for the reasoning.
 */
public enum MeetingStatus {

    /** A meeting created manually with an agenda (US12.1.1) — not part of the booking flow. */
    DRAFT,

    /**
     * A meeting created from a {@code roadmap.event.window.created} event, with candidate slots
     * proposed by the best-slot engine, awaiting organizer validation. No invitation has been
     * sent yet (US12.4.1).
     */
    PRE_RESERVED,

    /**
     * The organizer has validated a proposed (or manually adjusted) slot: invitations were sent
     * and {@code meetops.booking.confirmed} was published (US12.4.1).
     */
    CONFIRMED
}
