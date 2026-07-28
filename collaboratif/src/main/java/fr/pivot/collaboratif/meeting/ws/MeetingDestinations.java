package fr.pivot.collaboratif.meeting.ws;

import java.util.UUID;

/**
 * STOMP destination constants and helpers for the MeetOps meeting animation real-time channel
 * (US12.2.1) — exact calque of {@code fr.pivot.collaboratif.session.ws.SessionDestinations},
 * isolated under a {@code meeting} sub-path.
 */
public final class MeetingDestinations {

    /** Broker-side topic prefix every meeting animation broadcast is published under. */
    public static final String TOPIC_PREFIX = "/topic/collaboratif/meeting/";

    /** Application-side destination prefix — unused today (this channel is broadcast-only, no
     *  client-originated {@code SEND}; every write is a REST call), kept for symmetry with {@link
     *  fr.pivot.collaboratif.session.ws.SessionDestinations#APP_PREFIX} and future-proofing. */
    public static final String APP_PREFIX = "/app/collaboratif/meeting/";

    private MeetingDestinations() {
    }

    /**
     * Builds the broadcast topic for a given meeting.
     *
     * @param meetingId the meeting's UUID
     * @return the full STOMP topic destination, e.g. {@code /topic/collaboratif/meeting/{id}}
     */
    public static String topicFor(final UUID meetingId) {
        return TOPIC_PREFIX + meetingId;
    }

    /**
     * Extracts the meeting id from a subscribed/sent destination, if it matches this channel's
     * shape.
     *
     * @param destination the raw STOMP destination
     * @return the meeting UUID, or {@code null} if the destination does not belong to this channel
     */
    public static UUID meetingIdFrom(final String destination) {
        if (destination == null) {
            return null;
        }
        String prefix = destination.startsWith(TOPIC_PREFIX) ? TOPIC_PREFIX
                : destination.startsWith(APP_PREFIX) ? APP_PREFIX : null;
        if (prefix == null) {
            return null;
        }
        String remainder = destination.substring(prefix.length());
        int slash = remainder.indexOf('/');
        String rawId = slash >= 0 ? remainder.substring(0, slash) : remainder;
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
