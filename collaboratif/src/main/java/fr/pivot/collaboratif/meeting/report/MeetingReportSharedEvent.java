package fr.pivot.collaboratif.meeting.report;

import java.time.Instant;
import java.util.UUID;

/**
 * STOMP broadcast payload for {@code MEETING_REPORT_SHARED} (US12.3.1 AC7/AC8) — published on
 * {@code /topic/collaboratif/meeting/{meetingId}} when the organizer explicitly shares a closed
 * meeting's compte-rendu with the team via {@code POST .../report/share}. Distinct from {@link
 * MeetingReportReadyEvent}: that one fires automatically, exactly once, the instant the report is
 * frozen at closure ("le compte-rendu existe now") — this one fires only on the organizer's own
 * deliberate action, any number of times ("le compte-rendu a été partagé"), and is the real-time
 * signal every visible participant/team member's client uses to render the AC's "confirmation de
 * partage" (announced via {@code aria-live="polite"} on the sharer's own screen; every other
 * subscriber's client treats this as a notification).
 *
 * <p>Deliberately minimal, same AC Security rationale as {@link MeetingReportReadyEvent}: the full
 * report is never broadcast over the bus — every subscriber refetches it via {@code GET
 * .../report} using its own caller-scoped authorization.
 *
 * @param type       always {@link #EVENT_TYPE}
 * @param meetingId  the meeting whose report was just shared
 * @param sharedBy   the organizer's {@code public.users.id} who triggered the share
 * @param sharedAt   the instant the share was triggered
 */
public record MeetingReportSharedEvent(String type, UUID meetingId, Long sharedBy, Instant sharedAt) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "MEETING_REPORT_SHARED";

    /**
     * Creates the event.
     *
     * @param meetingId the meeting whose report was just shared
     * @param sharedBy  the organizer's {@code public.users.id} who triggered the share
     * @param sharedAt  the instant the share was triggered
     */
    public MeetingReportSharedEvent(final UUID meetingId, final Long sharedBy, final Instant sharedAt) {
        this(EVENT_TYPE, meetingId, sharedBy, sharedAt);
    }
}
