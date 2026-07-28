package fr.pivot.collaboratif.meeting;

/**
 * Lifecycle status of a {@link Meeting}.
 *
 * <p>{@link #DRAFT} is the only status a meeting is created in (US12.1.1, this US only covers
 * creation). {@link #CONFIRMED}, {@link #IN_PROGRESS} and {@link #ENDED} are added by US12.2.1
 * (animation), which resolves the "extend this enum or a separate one" question this class's
 * prior JavaDoc deliberately left open — a single lifecycle enum was chosen (over a distinct
 * animation-status type) since a meeting is never in two of these states at once and every
 * consuming query (module dashboards, KPIs) only ever needs one status column per meeting.
 *
 * <p><strong>{@code CONFIRMED} is not yet reachable in production</strong> — US12.4.1's roadmap
 * booking/confirmation flow (which would transition {@code DRAFT → CONFIRMED}) does not exist
 * yet, so every meeting created today stays {@code DRAFT} until animated. US12.2.1's AC-01
 * ("Given une réunion {@code CONFIRMED}...") is written against that future flow; until it lands,
 * {@link fr.pivot.collaboratif.meeting.MeetingAnimationService#start} accepts a start from either
 * {@code DRAFT} or {@code CONFIRMED} — both are "not yet started" pre-animation states, and
 * treating only {@code CONFIRMED} as startable would make {@code POST .../start} unusable for
 * every meeting this US's own predecessor (US12.1.1) can actually produce. This is a deliberate,
 * documented interpretation of a genuine spec/implementation gap, not a guess: revisit once
 * US12.4.1 defines what (if anything) {@code DRAFT} meetings are still allowed to do once
 * {@code CONFIRMED} exists as a real, reachable state.
 */
public enum MeetingStatus {

    /** A meeting created but not yet started/pre-reserved/confirmed. */
    DRAFT,

    /** A meeting confirmed and ready to start (US12.4.1 booking flow — not yet producible). */
    CONFIRMED,

    /** A meeting currently being animated — has a current agenda item and a running timer. */
    IN_PROGRESS,

    /** A meeting that has been concluded (via {@code POST .../end} or advancing past the last
     *  agenda item). Terminal — a meeting never leaves this status. */
    ENDED
}
