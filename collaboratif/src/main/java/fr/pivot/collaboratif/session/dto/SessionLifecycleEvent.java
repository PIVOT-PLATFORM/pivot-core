package fr.pivot.collaboratif.session.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} for {@code SESSION_PAUSED}/{@code
 * SESSION_RESUMED}/{@code SESSION_ENDED} (US19.1.2) — these three carry no payload beyond the
 * session id and the event type itself, unlike {@link SessionStartedEvent} (which includes the
 * full session).
 *
 * @param type      one of {@link #PAUSED}, {@link #RESUMED}, {@link #ENDED}
 * @param sessionId the session this event concerns
 */
public record SessionLifecycleEvent(String type, UUID sessionId) {

    /** Event type for a pause transition. */
    public static final String PAUSED = "SESSION_PAUSED";

    /** Event type for a resume transition. */
    public static final String RESUMED = "SESSION_RESUMED";

    /** Event type for an end transition. */
    public static final String ENDED = "SESSION_ENDED";
}
