package fr.pivot.collaboratif.meeting;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.MeetingForbiddenException;
import fr.pivot.collaboratif.exception.MeetingNotFoundException;
import fr.pivot.core.team.TeamMemberRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Resolves a {@link Meeting} for a caller, enforcing tenant isolation and the two distinct
 * authorization levels US12.2.1 requires: general visibility ({@code GET .../live}, AC-07) versus
 * animation authority ({@code start}/{@code agenda/next}/{@code end}/{@code actions}, AC-S2).
 * Mirrors {@code fr.pivot.collaboratif.session.SessionAccessService}'s identical split.
 *
 * <p><strong>Tenant resolution always runs first and is never distinguishable from "does not
 * exist"</strong> (AC-S1) — {@link #loadInTenant} is the single choke point every public method
 * of this class goes through, throwing {@link MeetingNotFoundException} (404) for both a
 * genuinely unknown id and one belonging to another tenant. Only once a meeting is confirmed to
 * exist in the caller's own tenant does {@link #resolveMeetingForOwnerOrAdmin} apply its
 * owner-or-admin check — a failure there is a genuine {@link MeetingForbiddenException} (403,
 * AC-S2), deliberately NOT folded into the same 404 {@code SessionAccessService} uses for its own
 * equivalent check, per this US's explicit AC wording.
 */
@Service
public class MeetingAccessService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final MeetingRepository meetingRepository;
    private final TeamMemberRepository teamMemberRepository;

    /**
     * Creates the service with its required dependencies.
     *
     * @param meetingRepository    repository for meeting lookups
     * @param teamMemberRepository repository used to check team membership (visibility only)
     */
    public MeetingAccessService(
            final MeetingRepository meetingRepository, final TeamMemberRepository teamMemberRepository) {
        this.meetingRepository = meetingRepository;
        this.teamMemberRepository = teamMemberRepository;
    }

    /**
     * Resolves a meeting for general/participant access ({@code GET .../live}, AC-07) — the
     * owner, or any member of the meeting's team when it has one.
     *
     * @param meetingId the meeting's UUID
     * @param principal the caller
     * @return the meeting
     * @throws MeetingNotFoundException if the meeting does not exist, belongs to another tenant,
     *                                   or the caller has no visibility into it (all 404,
     *                                   anti-enumeration)
     */
    public Meeting resolveMeetingForCaller(final UUID meetingId, final CollaboratifRequestPrincipal principal) {
        Meeting meeting = loadInTenant(meetingId, principal.tenantId());
        if (!isVisibleToCaller(meeting, principal)) {
            throw new MeetingNotFoundException();
        }
        return meeting;
    }

    /**
     * Resolves a meeting for animation actions ({@code start}/{@code agenda/next}/{@code end}/
     * {@code actions}, AC-S2) — the owner or a platform {@code ROLE_ADMIN} only. {@code teamId}
     * never grants this level of authority, same posture as {@code
     * SessionAccessService#resolveSessionForOwnerOrAdmin}.
     *
     * @param meetingId the meeting's UUID
     * @param principal the caller
     * @return the meeting
     * @throws MeetingNotFoundException  if the meeting does not exist or belongs to another
     *                                    tenant (404, AC-S1 — checked first, before role)
     * @throws MeetingForbiddenException if the meeting exists in the caller's tenant but the
     *                                    caller is neither the owner nor an admin (403, AC-S2)
     */
    public Meeting resolveMeetingForOwnerOrAdmin(final UUID meetingId, final CollaboratifRequestPrincipal principal) {
        Meeting meeting = loadInTenant(meetingId, principal.tenantId());
        boolean isOwner = meeting.getCreatedBy().equals(principal.userId());
        boolean isAdmin = ROLE_ADMIN.equals(principal.role());
        if (!isOwner && !isAdmin) {
            throw new MeetingForbiddenException(
                    "MEETING_FACILITATOR_ONLY", "Caller is not the meeting's owner or an admin");
        }
        return meeting;
    }

    private boolean isVisibleToCaller(final Meeting meeting, final CollaboratifRequestPrincipal principal) {
        boolean isOwner = meeting.getCreatedBy().equals(principal.userId());
        boolean isTeamMember = meeting.getTeamId() != null
                && teamMemberRepository.findByTeamIdAndUserId(meeting.getTeamId(), principal.userId()).isPresent();
        return isOwner || isTeamMember;
    }

    private Meeting loadInTenant(final UUID meetingId, final Long tenantId) {
        Meeting meeting = meetingRepository.findById(meetingId).orElseThrow(MeetingNotFoundException::new);
        if (!meeting.getTenantId().equals(tenantId)) {
            throw new MeetingNotFoundException();
        }
        return meeting;
    }
}
