package fr.pivot.collaboratif.meeting.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/meeting/{id}} every second by {@code
 * MeetingTimerScheduler} for the current agenda item of every {@code IN_PROGRESS} meeting
 * (US12.2.1 AC-02/AC-04). Deliberately NOT the authority — a reconciliation signal only, so the
 * UI can safely render a locally-ticking display between two broadcasts and simply re-anchor on
 * each new one (a missed tick degrades gracefully, never breaks the display, per the "Timer 1 s"
 * implementation note). {@code serverTime} lets a client compute and correct its own clock offset
 * ({@code offset = serverTime - localClockAtReceipt}) rather than trusting local receipt time,
 * per the AC's anti-drift design.
 *
 * @param type             always {@link #EVENT_TYPE}
 * @param meetingId        the meeting this tick concerns
 * @param agendaItemId     the current agenda item this tick concerns
 * @param elapsedSeconds   server-computed seconds since the item became current
 * @param remainingSeconds server-computed allotted-minus-elapsed; negative in overtime
 * @param overtime         server-computed {@code true} once {@code remainingSeconds} goes
 *                         negative (AC-04)
 * @param overtimeSeconds  server-computed {@code max(0, -remainingSeconds)}; {@code > 0} exactly
 *                         when {@code overtime} is {@code true}
 * @param serverTime       the server instant this tick was computed at
 */
public record TimerTickEvent(
        String type, UUID meetingId, UUID agendaItemId, long elapsedSeconds, long remainingSeconds,
        boolean overtime, long overtimeSeconds, Instant serverTime) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "TIMER_TICK";

    /**
     * Creates the event.
     *
     * @param meetingId        the meeting this tick concerns
     * @param agendaItemId     the current agenda item this tick concerns
     * @param elapsedSeconds   server-computed seconds since the item became current
     * @param remainingSeconds server-computed allotted-minus-elapsed; negative in overtime
     * @param overtime         server-computed overtime flag
     * @param overtimeSeconds  server-computed {@code max(0, -remainingSeconds)}
     * @param serverTime       the server instant this tick was computed at
     */
    public TimerTickEvent(
            final UUID meetingId, final UUID agendaItemId, final long elapsedSeconds,
            final long remainingSeconds, final boolean overtime, final long overtimeSeconds,
            final Instant serverTime) {
        this(EVENT_TYPE, meetingId, agendaItemId, elapsedSeconds, remainingSeconds, overtime, overtimeSeconds,
                serverTime);
    }
}
