package fr.pivot.collaboratif.meeting;

/**
 * Lifecycle status of a {@link Meeting}.
 *
 * <p>{@link #DRAFT} is the status a manually-created meeting-with-agenda starts in (US12.1.1).
 * {@link #IN_PROGRESS} and {@link #ENDED} are added by US12.2.1 (animation), which resolves the
 * "extend this enum or a separate one" question this class's prior JavaDoc deliberately left
 * open — a single lifecycle enum was chosen (over a distinct animation-status type) since a
 * meeting is never in two of these states at once and every consuming query (module dashboards,
 * KPIs) only ever needs one status column per meeting.
 *
 * <p>{@link #PRE_RESERVED} and {@link #CONFIRMED} are added by US12.4.1 (roadmap booking flow,
 * E12): a meeting created from a {@code roadmap.event.window.created} event starts life in
 * {@code PRE_RESERVED} — a draft with proposed slots, no invitation sent yet — and moves to
 * {@code CONFIRMED} once the organizer validates a slot (invitation sent, {@code
 * meetops.booking.confirmed} published). This is purely additive on top of {@link #DRAFT} — the
 * column stays a plain {@code VARCHAR}, widened via {@code V20__meetops_booking.sql}'s {@code
 * chk_meeting_status} constraint change, so nothing about US12.1.1's own DRAFT flow is affected.
 *
 * <p>{@code CONFIRMED} is now reachable via the booking flow (US12.4.1). {@link
 * fr.pivot.collaboratif.meeting.MeetingAnimationService#start} accepts a start from either {@code
 * DRAFT} or {@code CONFIRMED} — both are "not yet started" pre-animation states, whichever flow
 * produced the meeting (manual agenda creation vs. roadmap booking).
 *
 * <p>No {@code CANCELLED} value: a {@code roadmap.event.window.deleted} on a non-confirmed
 * booking-flow meeting deletes the row outright (cascading its {@code proposed_slots}) rather
 * than flipping a status — see {@code BookingService#handleWindowDeleted}'s Javadoc.
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
     * A meeting confirmed and ready to start — either a manually-created meeting validated, or a
     * booking-flow meeting whose organizer validated a proposed (or manually adjusted) slot:
     * invitations were sent and {@code meetops.booking.confirmed} was published (US12.4.1).
     */
    CONFIRMED,

    /** A meeting currently being animated — has a current agenda item and a running timer. */
    IN_PROGRESS,

    /** A meeting that has been concluded (via {@code POST .../end} or advancing past the last
     *  agenda item). Terminal — a meeting never leaves this status. */
    ENDED
}
