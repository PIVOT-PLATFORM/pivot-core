package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityEventMember;

import java.util.UUID;

/**
 * Response payload for a {@link CapacityEventMember} (F11.2).
 *
 * @param id            unique identifier of the member
 * @param eventId       the owning event's identifier
 * @param teamMemberRef the linked {@code public.team_members.id}, or {@code null}
 * @param name          the member's display name (roster snapshot)
 * @param role          the member's role (roster snapshot), or {@code null}
 * @param quotite       the full-time-equivalent quotity
 * @param focusFactor   the per-member focus factor override, or {@code null}
 * @param locality      the member's locality, or {@code null}
 * @param excluded      whether this member is excluded from the event's capacity computation
 * @param position      the display order within the event
 */
public record CapacityMemberResponse(
        UUID id,
        UUID eventId,
        Long teamMemberRef,
        String name,
        String role,
        double quotite,
        Double focusFactor,
        String locality,
        boolean excluded,
        int position) {

    /**
     * Factory method that creates a {@link CapacityMemberResponse} from a
     * {@link CapacityEventMember} entity.
     *
     * @param member the member entity
     * @return a populated response record
     */
    public static CapacityMemberResponse from(final CapacityEventMember member) {
        return new CapacityMemberResponse(
                member.getId(),
                member.getEventId(),
                member.getTeamMemberRef(),
                member.getName(),
                member.getRole(),
                member.getQuotite(),
                member.getFocusFactor(),
                member.getLocality(),
                member.isExcluded(),
                member.getPosition());
    }
}
