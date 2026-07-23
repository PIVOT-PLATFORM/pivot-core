package fr.pivot.collaboratif.session.ws;

import java.util.UUID;

/**
 * STOMP destination constants and helpers for the Module Session real-time channel
 * (US19.1.2/US19.2.1) — mirrors {@code fr.pivot.collaboratif.whiteboard.ws} destination
 * conventions, isolated under a {@code session} sub-path.
 */
public final class SessionDestinations {

    /** Broker-side topic prefix every session broadcast is published under. */
    public static final String TOPIC_PREFIX = "/topic/collaboratif/session/";

    /** Application-side destination prefix clients send messages to. */
    public static final String APP_PREFIX = "/app/collaboratif/session/";

    private SessionDestinations() {
    }

    /**
     * Builds the broadcast topic for a given session.
     *
     * @param sessionId the session's UUID
     * @return the full STOMP topic destination, e.g. {@code /topic/collaboratif/session/{id}}
     */
    public static String topicFor(final UUID sessionId) {
        return TOPIC_PREFIX + sessionId;
    }

    /**
     * Extracts the session id from a subscribed/sent destination, if it matches this channel's
     * shape.
     *
     * @param destination the raw STOMP destination
     * @return the session UUID, or {@code null} if the destination does not belong to this channel
     */
    public static UUID sessionIdFrom(final String destination) {
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
