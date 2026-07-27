package fr.pivot.collaboratif.meeting;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.MeetOpsModuleDisabledException;
import fr.pivot.collaboratif.exception.MeetingTeamNotFoundException;
import fr.pivot.collaboratif.meeting.dto.AgendaDurationMismatch;
import fr.pivot.collaboratif.meeting.dto.AgendaItemRequest;
import fr.pivot.collaboratif.meeting.dto.CreateMeetingRequest;
import fr.pivot.collaboratif.meeting.dto.MeetingResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Business logic for meeting creation (US12.1.1).
 *
 * <p>Tenant and user identity always come from the resolved {@link CollaboratifRequestPrincipal}
 * (EN08.3) — never from the request body (AC8). {@code teamId}, when supplied, is resolved
 * against the caller's own tenant only ({@link MeetingRepository#teamBelongsToTenant}); an
 * unknown or cross-tenant {@code teamId} resolves to {@link MeetingTeamNotFoundException} (404,
 * AC7 — never 403, to avoid confirming the existence of a team in another tenant, same posture as
 * {@code fr.pivot.collaboratif.whiteboard.board.BoardService}).
 */
@Service
public class MeetingService {

    private final MeetingRepository meetingRepository;
    private final MeetOpsModuleCheck moduleCheck;

    /**
     * Creates the service with its required dependencies.
     *
     * @param meetingRepository repository for meeting (and cascaded agenda item) persistence
     * @param moduleCheck       resolves whether the MeetOps module is active for a tenant
     */
    public MeetingService(final MeetingRepository meetingRepository, final MeetOpsModuleCheck moduleCheck) {
        this.meetingRepository = meetingRepository;
        this.moduleCheck = moduleCheck;
    }

    /**
     * Creates a new meeting in {@link MeetingStatus#DRAFT}, with its agenda items, atomically
     * (US12.1.1 AC1/AC2). A validation failure on any agenda item is expected to have already
     * been rejected by Bean Validation before this method is even invoked (AC6) — the whole
     * aggregate (meeting + agenda items) is persisted by a single {@link
     * MeetingRepository#save} call, cascading, so there is no partial-write window to roll back
     * from at this layer.
     *
     * @param request   the creation request
     * @param principal the caller — supplies {@code tenantId} (never the request body, AC8) and
     *                  becomes {@code createdBy}
     * @return the created meeting, with its ordered agenda and the on-the-fly duration mismatch
     *     warning (AC3), if any
     * @throws MeetOpsModuleDisabledException if the MeetOps module is inactive for the caller's
     *                                         tenant (AC8)
     * @throws MeetingTeamNotFoundException   if {@code request.teamId()} does not resolve to a
     *                                         team of the caller's tenant (AC7)
     */
    @Transactional
    public MeetingResponse create(final CreateMeetingRequest request, final CollaboratifRequestPrincipal principal) {
        if (!moduleCheck.isEnabled(principal.tenantId())) {
            throw new MeetOpsModuleDisabledException(principal.tenantId());
        }
        Long teamId = request.teamId();
        if (teamId != null && !meetingRepository.teamBelongsToTenant(teamId, principal.tenantId())) {
            throw new MeetingTeamNotFoundException(teamId);
        }

        Instant now = Instant.now();
        Meeting meeting = new Meeting(
                principal.tenantId(), teamId, request.title(), request.scheduledAt(),
                request.totalDurationMinutes(), principal.userId(), now);

        List<AgendaItemRequest> agendaItems = request.agendaItems() != null ? request.agendaItems() : List.of();
        for (AgendaItemRequest item : agendaItems) {
            meeting.addAgendaItem(
                    item.title(), item.durationMinutes(), AgendaItemType.valueOf(item.type()), item.facilitator());
        }

        Meeting saved = meetingRepository.save(meeting);
        return MeetingResponse.from(saved, reconcile(request.totalDurationMinutes(), agendaItems));
    }

    /**
     * Computes the AC3 duration reconciliation warning, or {@code null} when not applicable
     * (AC4 — no agenda items at all — or the sum already matches the total).
     *
     * @param totalDurationMinutes the meeting's planned total duration
     * @param agendaItems          the requested agenda items (never {@code null}, possibly empty)
     * @return the mismatch details, or {@code null}
     */
    private AgendaDurationMismatch reconcile(
            final Integer totalDurationMinutes, final List<AgendaItemRequest> agendaItems) {
        if (agendaItems.isEmpty()) {
            return null;
        }
        int sumMinutes = agendaItems.stream().mapToInt(AgendaItemRequest::durationMinutes).sum();
        if (sumMinutes == totalDurationMinutes) {
            return null;
        }
        return new AgendaDurationMismatch(totalDurationMinutes, sumMinutes, sumMinutes - totalDurationMinutes);
    }
}
