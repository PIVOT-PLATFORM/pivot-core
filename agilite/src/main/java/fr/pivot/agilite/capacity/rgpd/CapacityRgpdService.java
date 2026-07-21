package fr.pivot.agilite.capacity.rgpd;

import fr.pivot.agilite.capacity.CapacityAbsence;
import fr.pivot.agilite.capacity.CapacityAbsenceRepository;
import fr.pivot.agilite.capacity.CapacityEvent;
import fr.pivot.agilite.capacity.CapacityEventMember;
import fr.pivot.agilite.capacity.CapacityEventMemberRepository;
import fr.pivot.agilite.capacity.CapacityEventRepository;
import fr.pivot.agilite.capacity.exception.CapacityAccessDeniedException;
import fr.pivot.core.team.TeamMember;
import fr.pivot.core.team.TeamMemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for a capacity data subject's rights of access, portability, and erasure
 * (US11.8.1, RGPD Art. 15/17/20), scoped to the capacity data this module owns — {@link
 * CapacityAbsence} rows only, never anything from {@code public.team_members} itself (owned by
 * {@code pivot-core-starter}, out of this module's erasure reach).
 *
 * <p>{@code teamMemberRef} identifies the data subject as {@code public.team_members.id} — the
 * same roster reference {@link CapacityEventMember#getTeamMemberRef()} snapshots at add-time (see
 * that entity's Javadoc). Resolving it first to its {@link TeamMember} row yields the owning
 * {@code teamId}, which is then checked against the caller's own membership of that same team —
 * identical inline tenant/team-membership pattern already used by {@code CapacityMemberService}:
 * a {@code teamMemberRef} that does not exist, or whose team the caller does not belong to
 * (including every cross-tenant case, since a caller can never be a member of another tenant's
 * team), collapses to the same generic 404 to avoid confirming cross-tenant existence.
 */
@Service
@Transactional
public class CapacityRgpdService {

    private static final String NOT_FOUND_MESSAGE = "Capacity data subject not found";

    private final CapacityEventRepository eventRepository;
    private final CapacityEventMemberRepository memberRepository;
    private final CapacityAbsenceRepository absenceRepository;
    private final TeamMemberRepository teamMemberRepository;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param eventRepository      capacity event persistence
     * @param memberRepository     capacity event member persistence
     * @param absenceRepository    capacity absence persistence
     * @param teamMemberRepository {@code pivot-core-starter}'s team membership persistence
     */
    public CapacityRgpdService(
            final CapacityEventRepository eventRepository,
            final CapacityEventMemberRepository memberRepository,
            final CapacityAbsenceRepository absenceRepository,
            final TeamMemberRepository teamMemberRepository) {
        this.eventRepository = eventRepository;
        this.memberRepository = memberRepository;
        this.absenceRepository = absenceRepository;
        this.teamMemberRepository = teamMemberRepository;
    }

    /**
     * Exports every capacity absence period recorded for one person, across every capacity event
     * of the team the caller shares with them (right of access/portability, RGPD Art. 15/20).
     *
     * @param teamMemberRef the data subject's {@code public.team_members.id}
     * @param callerId      the authenticated caller's user id
     * @param tenantId      the authenticated caller's tenant id
     * @return the data subject's absence periods, aggregate-safe (no motif/health field exists in
     *         this schema)
     * @throws ResponseStatusException with HTTP 404 if {@code teamMemberRef} does not exist, or
     *                                 the caller is not a member of that person's team
     */
    public CapacityRgpdExportResponse exportData(
            final Long teamMemberRef, final Long callerId, final Long tenantId) {
        TeamMember subject = resolveSubject(teamMemberRef, callerId);

        List<CapacityRgpdAbsenceResponse> absences = matchingMembers(subject.getTeamId(), tenantId, teamMemberRef)
                .stream()
                .flatMap(member -> absenceRepository.findByEventMemberId(member.getId()).stream()
                        .map(absence -> toResponse(member, absence)))
                .toList();

        return new CapacityRgpdExportResponse(teamMemberRef, absences);
    }

    /**
     * Erases every capacity absence period recorded for one person, across every capacity event
     * of the team the caller shares with them (right to erasure, RGPD Art. 17).
     *
     * @param teamMemberRef the data subject's {@code public.team_members.id}
     * @param callerId      the authenticated caller's user id
     * @param tenantId      the authenticated caller's tenant id
     * @throws ResponseStatusException      with HTTP 404 if {@code teamMemberRef} does not exist,
     *                                      or the caller is not a member of that person's team
     * @throws CapacityAccessDeniedException if the caller lacks a write-capable role
     */
    public void eraseData(final Long teamMemberRef, final Long callerId, final Long tenantId) {
        TeamMember subject = resolveSubject(teamMemberRef, callerId);
        requireWriteAccess(subject.getTeamId(), callerId);

        List<CapacityEventMember> members = matchingMembers(subject.getTeamId(), tenantId, teamMemberRef);
        List<UUID> absenceIds = members.stream()
                .flatMap(member -> absenceRepository.findByEventMemberId(member.getId()).stream())
                .map(CapacityAbsence::getId)
                .collect(Collectors.toList());
        absenceRepository.deleteAllById(absenceIds);
    }

    /**
     * Resolves the {@code teamMemberRef} to its {@link TeamMember} row and checks the caller
     * shares that same team — the tenant/team-membership check, collapsed to a generic 404.
     *
     * @param teamMemberRef the data subject's {@code public.team_members.id}
     * @param callerId      the authenticated caller's user id
     * @return the resolved subject's team membership row
     * @throws ResponseStatusException with HTTP 404 if not found or not shared with the caller
     */
    private TeamMember resolveSubject(final Long teamMemberRef, final Long callerId) {
        TeamMember subject = teamMemberRepository.findById(teamMemberRef)
                .orElseThrow(CapacityRgpdService::notFound);
        teamMemberRepository.findByTeamIdAndUserId(subject.getTeamId(), callerId)
                .orElseThrow(CapacityRgpdService::notFound);
        return subject;
    }

    /**
     * Checks the caller's own membership of the subject's team holds a write-capable role
     * ({@link TeamMember#ROLE_RESPONSABLE} or {@link TeamMember#ROLE_ADJOINT}).
     *
     * @param teamId   the data subject's team, already confirmed shared with the caller
     * @param callerId the authenticated caller's user id
     * @throws CapacityAccessDeniedException if the caller lacks a write-capable role
     */
    private void requireWriteAccess(final Long teamId, final Long callerId) {
        // resolveSubject already proved the caller belongs to this team.
        String role = teamMemberRepository.findByTeamIdAndUserId(teamId, callerId)
                .map(TeamMember::getRole)
                .orElse(TeamMember.ROLE_MEMBRE);
        if (!TeamMember.ROLE_RESPONSABLE.equals(role) && !TeamMember.ROLE_ADJOINT.equals(role)) {
            throw new CapacityAccessDeniedException(
                    "Caller's role does not grant erasure of this capacity data subject's data");
        }
    }

    /**
     * Finds every {@link CapacityEventMember} row referencing {@code teamMemberRef}, across every
     * capacity event of {@code teamId} scoped to {@code tenantId}.
     *
     * @param teamId        the data subject's team
     * @param tenantId      the caller's tenant id
     * @param teamMemberRef the data subject's {@code public.team_members.id}
     * @return the matching member rows, one per capacity event the person was added to
     */
    private List<CapacityEventMember> matchingMembers(
            final Long teamId, final Long tenantId, final Long teamMemberRef) {
        return eventRepository.findByTeamIdAndTenantId(teamId, tenantId).stream()
                .map(CapacityEvent::getId)
                .flatMap(eventId -> memberRepository.findByEventIdOrderByPositionAsc(eventId).stream())
                .filter(member -> teamMemberRef.equals(member.getTeamMemberRef()))
                .toList();
    }

    /**
     * Builds the response record for one absence, carrying its owning event id (via the member's
     * event id) rather than the member id — the data subject has no reason to see this module's
     * internal member-row identifier.
     *
     * @param member  the owning event member, already resolved
     * @param absence the absence entity
     * @return the populated response record
     */
    private static CapacityRgpdAbsenceResponse toResponse(
            final CapacityEventMember member, final CapacityAbsence absence) {
        return new CapacityRgpdAbsenceResponse(
                absence.getId(),
                member.getEventId(),
                absence.getStartDate(),
                absence.getEndDate(),
                absence.getFraction(),
                absence.getSource());
    }

    /**
     * Builds the generic 404 thrown for every "not found for this caller" case — never leaks
     * whether {@code teamMemberRef} exists at all under another tenant/team.
     *
     * @return a {@link ResponseStatusException} carrying HTTP 404 and a generic message
     */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
    }
}
