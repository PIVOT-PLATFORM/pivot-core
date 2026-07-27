package fr.pivot.collaboratif.meetops.booking.ws;

import java.util.UUID;

/**
 * Pushed on {@code /topic/collaboratif/meeting/{id}} when a {@code PRE_RESERVED} meeting is
 * deleted on {@code window.deleted} (US12.4.1 "annulation") — the room otherwise falls silent
 * forever with no terminal signal, since {@link MeetingRealtimePublisher#publish} cannot be used
 * once the meeting row no longer exists to build a {@code MeetingBookingResponse} from.
 * Deliberately minimal, mirroring {@code MeetingReportSharedEvent} (US12.3.1) — just enough for a
 * subscribed client to know the room is over and stop listening/show a cancellation notice.
 *
 * @param type      always {@value #EVENT_TYPE}
 * @param meetingId the cancelled meeting's id
 * @param eventRef  the upstream roadmap event correlation id that triggered the cancellation
 */
public record MeetingCancelledEvent(String type, UUID meetingId, String eventRef) {

    public static final String EVENT_TYPE = "MEETING_CANCELLED";

    public MeetingCancelledEvent(final UUID meetingId, final String eventRef) {
        this(EVENT_TYPE, meetingId, eventRef);
    }
}
