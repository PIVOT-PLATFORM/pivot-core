package fr.pivot.agilite.standup.dto;

import fr.pivot.agilite.standup.StandupSession;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response payload representing a standup session and its participants (US10.1.1/US10.1.2).
 *
 * @param id                    unique identifier of the session
 * @param teamId                {@code public.teams.id} this session belongs to
 * @param tenantId              {@code public.tenants.id} of the tenant that owns this session
 * @param name                  human-readable session name
 * @param status                lifecycle status
 * @param currentIndex          {@code order} of the currently/last-speaking participant
 * @param timePerPersonSeconds  configured speaking time per participant, in seconds
 * @param participants          the session's participants, in speaking order
 * @param startedAt             timestamp the session was started, or {@code null}
 * @param endedAt               timestamp the session ended, or {@code null}
 * @param createdAt             timestamp when the session was created
 * @param updatedAt             timestamp of the last session update
 */
public record StandupSessionResponse(
        UUID id,
        Long teamId,
        Long tenantId,
        String name,
        String status,
        int currentIndex,
        int timePerPersonSeconds,
        List<StandupParticipantResponse> participants,
        Instant startedAt,
        Instant endedAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Factory method that creates a {@link StandupSessionResponse} from a {@link StandupSession}
     * entity.
     *
     * @param session the session entity
     * @return a populated response record
     */
    public static StandupSessionResponse from(final StandupSession session) {
        return new StandupSessionResponse(
                session.getId(),
                session.getTeamId(),
                session.getTenantId(),
                session.getName(),
                session.getStatus().name(),
                session.getCurrentIndex(),
                session.getTimePerPersonSeconds(),
                session.getParticipants().stream().map(StandupParticipantResponse::from).toList(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getCreatedAt(),
                session.getUpdatedAt());
    }
}
