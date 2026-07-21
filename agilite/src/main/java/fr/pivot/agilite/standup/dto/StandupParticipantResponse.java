package fr.pivot.agilite.standup.dto;

import fr.pivot.agilite.standup.StandupParticipant;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload representing a single standup session participant (US10.1.1).
 *
 * @param id             unique identifier of the participant row
 * @param teamMemberId   referenced {@code public.team_members.id}
 * @param name           denormalized display name
 * @param order          {@code 0}-based speaking-turn order
 * @param status         turn-taking status
 * @param speakingAt     timestamp this participant started speaking, or {@code null}
 * @param doneSpeaking   timestamp this participant finished speaking/was skipped, or {@code null}
 * @param extraSeconds   cumulative extra seconds granted via {@code extend} (US10.2.2)
 */
public record StandupParticipantResponse(
        UUID id,
        Long teamMemberId,
        String name,
        int order,
        String status,
        Instant speakingAt,
        Instant doneSpeaking,
        int extraSeconds) {

    /**
     * Factory method that creates a {@link StandupParticipantResponse} from a {@link
     * StandupParticipant} entity.
     *
     * @param participant the participant entity
     * @return a populated response record
     */
    public static StandupParticipantResponse from(final StandupParticipant participant) {
        return new StandupParticipantResponse(
                participant.getId(),
                participant.getTeamMemberId(),
                participant.getName(),
                participant.getParticipantOrder(),
                participant.getStatus().name(),
                participant.getSpeakingAt(),
                participant.getDoneSpeaking(),
                participant.getExtraSeconds());
    }
}
