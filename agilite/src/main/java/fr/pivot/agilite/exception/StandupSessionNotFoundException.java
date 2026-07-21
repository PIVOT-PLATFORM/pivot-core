package fr.pivot.agilite.exception;

import java.util.UUID;

/**
 * Thrown when a standup session does not exist, belongs to another tenant, or the caller is not
 * a member of its team (US10.1.1/US10.1.2).
 *
 * <p>Using a single exception for all three cases prevents cross-tenant/cross-team information
 * disclosure (the caller cannot distinguish "session doesn't exist" from "session exists but you
 * can't see it") — same posture as {@link WheelNotFoundException}.
 */
public class StandupSessionNotFoundException extends RuntimeException {

    /**
     * Creates a not-found exception for the given session identifier.
     *
     * @param sessionId the UUID of the session that could not be found or accessed
     */
    public StandupSessionNotFoundException(final UUID sessionId) {
        super("Standup session not found: " + sessionId);
    }
}
