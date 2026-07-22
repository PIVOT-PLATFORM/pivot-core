package fr.pivot.agilite.capacity.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response payload for a single event roster member (US11.2.1), including their absences
 * (US11.2.2).
 *
 * @param id                  the roster row's id
 * @param teamMemberId        the source {@code public.team_members.id}
 * @param name                the member's denormalized display name
 * @param availabilityPercent the availability percentage, {@code [10, 100]}
 * @param excluded            whether the member is excluded from capacity calculations
 * @param absences            the member's absences, ordered by {@code dateDebut}
 */
public record MemberResponse(
        UUID id, Long teamMemberId, String name, int availabilityPercent, boolean excluded, List<AbsenceResponse> absences) {
}
