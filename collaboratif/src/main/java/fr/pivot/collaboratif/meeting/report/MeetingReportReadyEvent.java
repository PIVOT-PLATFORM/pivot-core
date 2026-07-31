package fr.pivot.collaboratif.meeting.report;

import java.time.Instant;
import java.util.UUID;

/**
 * STOMP broadcast payload for {@code MEETING_REPORT_READY} (US12.3.1) — published on {@code
 * /topic/collaboratif/meeting/{meetingId}} exactly once, at closure, once the frozen snapshot has
 * been persisted. Carries an explicit {@code type} discriminator field, same shape convention as
 * every other event on this room ({@code MeetingStartedEvent}, {@code MeetingEndedEvent}, ...) —
 * the frontend's {@code MeetingEvent} union switches on it.
 *
 * <p>Deliberately minimal — the full report is <strong>never</strong> broadcast over the bus (AC
 * Security note: "ne pas diffuser le contenu complet"); every subscriber refetches it via
 * {@code GET .../report} using its own caller-scoped authorization.
 *
 * @param type        always {@link #EVENT_TYPE}
 * @param meetingId   the meeting that was just closed
 * @param generatedAt the freeze instant
 * @param draft       always {@code false} — included for shape symmetry with a future live
 *                    "draft updated" event, not because this event is ever emitted for a draft
 */
public record MeetingReportReadyEvent(String type, UUID meetingId, Instant generatedAt, boolean draft) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "MEETING_REPORT_READY";

    /**
     * Creates the event.
     *
     * @param meetingId   the meeting that was just closed
     * @param generatedAt the freeze instant
     * @param draft       always {@code false}
     */
    public MeetingReportReadyEvent(final UUID meetingId, final Instant generatedAt, final boolean draft) {
        this(EVENT_TYPE, meetingId, generatedAt, draft);
    }
}
