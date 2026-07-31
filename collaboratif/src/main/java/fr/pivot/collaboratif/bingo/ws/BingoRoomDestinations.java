package fr.pivot.collaboratif.bingo.ws;

import java.util.UUID;

/**
 * STOMP destination constants and helpers for the Bingo real-time channel (US47.1.1, AC-47.1.1-06).
 */
public final class BingoRoomDestinations {

    /** Broker-side topic prefix every room broadcast is published under. */
    public static final String TOPIC_ROOM_PREFIX = "/topic/collaboratif/bingo/";

    /** Application-side destination prefix clients send messages to. */
    public static final String APP_ROOM_PREFIX = "/app/collaboratif/bingo/";

    private BingoRoomDestinations() {
    }

    /**
     * Builds the broadcast topic for a given room.
     *
     * @param roomId the room's UUID
     * @return the full STOMP topic destination, e.g. {@code /topic/collaboratif/bingo/{id}}
     */
    public static String roomTopic(final UUID roomId) {
        return TOPIC_ROOM_PREFIX + roomId;
    }

    /**
     * Extracts the room id from a SUBSCRIBE/SEND destination under either prefix.
     *
     * @param destination the raw STOMP destination
     * @param prefix      the prefix to strip ({@link #TOPIC_ROOM_PREFIX} or {@link #APP_ROOM_PREFIX})
     * @return the room UUID string (possibly with a trailing sub-path not yet stripped), or
     *     {@code null} if the destination is shorter than the prefix
     */
    public static String extractRoomId(final String destination, final String prefix) {
        String after = destination.substring(prefix.length());
        if (after.isEmpty()) {
            return null;
        }
        int slash = after.indexOf('/');
        return slash < 0 ? after : after.substring(0, slash);
    }
}
