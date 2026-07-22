package fr.pivot.agilite.standup.ws;

import java.util.UUID;

/**
 * Single source of truth for STOMP destination naming of standup sessions (US10.1.2).
 *
 * <p><strong>Mirrors {@code fr.pivot.agilite.wheel.ws.WheelDestinations}</strong> exactly — same
 * slash-separated naming convention, registered as an additional, disjoint prefix on the same
 * in-process {@code SimpleBroker} as planning-poker rooms, retro sessions and wheels (see {@code
 * fr.pivot.agilite.config.WebSocketConfig}). Deliberately **not** built on top of EN19.2
 * (WebSocket room isolation, unimplemented) — see the pivot-docs US10.1.2 AC file's "Notes
 * d'implémentation" for the Gate 1 rationale: every {@code agilite.*} domain builds its own
 * lightweight room isolation, this is the fourth precedent (poker/retro/wheel/standup).
 *
 * <p>Like wheels, a standup session has no client-to-server application destination — every
 * mutation ({@code start}/{@code next}/{@code end}/{@code skip}/{@code extend}/{@code reorder})
 * is a REST call, computed and persisted server-side; this class therefore only ever defines a
 * broadcast topic, no {@code /app/agilite/standup/...} prefix.
 *
 * @see fr.pivot.agilite.config.WebSocketConfig
 * @see StandupChannelInterceptor
 */
public final class StandupDestinations {

    /**
     * Prefix for STOMP topic destinations broadcasting a standup session's lifecycle events to
     * every subscriber currently viewing that session. Full destination:
     * {@code /topic/agilite/standup/{sessionId}}.
     */
    public static final String TOPIC_STANDUP_PREFIX = "/topic/agilite/standup/";

    private StandupDestinations() {
    }

    /**
     * Builds the standup session broadcast topic destination — the one every viewer of a session
     * subscribes to.
     *
     * @param sessionId the session's identifier
     * @return {@code /topic/agilite/standup/{sessionId}}
     */
    public static String sessionTopic(final UUID sessionId) {
        return TOPIC_STANDUP_PREFIX + sessionId;
    }

    /**
     * Extracts the session id segment immediately following {@link #TOPIC_STANDUP_PREFIX} in
     * {@code destination}, stopping at the next {@code /} if the destination has a sub-path
     * beyond the session id (none exists today, but this mirrors the wheel precedent for forward
     * compatibility).
     *
     * @param destination the full STOMP destination
     * @return the raw session id segment, or {@code null} if {@code destination} is exactly the
     *         prefix with nothing following it
     */
    public static String extractSessionId(final String destination) {
        String after = destination.substring(TOPIC_STANDUP_PREFIX.length());
        if (after.isEmpty()) {
            return null;
        }
        int slash = after.indexOf('/');
        return slash < 0 ? after : after.substring(0, slash);
    }
}
