package fr.pivot.collaboratif.meetops.booking.ws;

import java.util.UUID;

/**
 * STOMP destination constants and helpers for the MeetOps booking real-time channel (US12.4.1) —
 * mirrors {@code fr.pivot.collaboratif.session.ws.SessionDestinations}'s destination conventions,
 * isolated under a {@code meeting} sub-path.
 */
public final class MeetingDestinations {

    /** Broker-side topic prefix every booking-flow broadcast is published under. */
    public static final String TOPIC_PREFIX = "/topic/collaboratif/meeting/";

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
     * Extracts the meeting id from a subscribed destination, if it matches this channel's shape.
     *
     * @param destination the raw STOMP destination
     * @return the meeting UUID, or {@code null} if the destination does not belong to this channel
     */
    public static UUID meetingIdFrom(final String destination) {
        if (destination == null || !destination.startsWith(TOPIC_PREFIX)) {
            return null;
        }
        String remainder = destination.substring(TOPIC_PREFIX.length());
        int slash = remainder.indexOf('/');
        String rawId = slash >= 0 ? remainder.substring(0, slash) : remainder;
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
