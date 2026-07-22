package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.AbsenceResponse;
import fr.pivot.agilite.capacity.dto.CreateAbsenceRequest;
import fr.pivot.agilite.exception.CapacityNotFoundException;
import fr.pivot.agilite.exception.CapacityValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Business logic for recording/removing a roster member's absences (US11.2.2).
 *
 * <p>RGPD minimisation — see {@link CapacityAbsence}'s Javadoc: this service, its DTOs, and its
 * entity carry no reason/category/comment field whatsoever.
 */
@Service
@Transactional
public class CapacityAbsenceService {

    private final CapacityEventService eventService;
    private final CapacityEventMemberRepository memberRepository;
    private final CapacityAbsenceRepository absenceRepository;

    /**
     * Creates the service with its required dependencies.
     *
     * @param eventService       shared event access resolution
     * @param memberRepository   repository for roster member persistence
     * @param absenceRepository  repository for absence persistence
     */
    public CapacityAbsenceService(
            final CapacityEventService eventService,
            final CapacityEventMemberRepository memberRepository,
            final CapacityAbsenceRepository absenceRepository) {
        this.eventService = eventService;
        this.memberRepository = memberRepository;
        this.absenceRepository = absenceRepository;
    }

    /**
     * Records a new absence for a roster member, validating it overlaps the event's period.
     *
     * @param eventId      the event UUID
     * @param memberId     the roster member UUID
     * @param request      the creation request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the created absence's response
     */
    public AbsenceResponse create(
            final UUID eventId,
            final UUID memberId,
            final CreateAbsenceRequest request,
            final Long callerUserId,
            final Long tenantId) {
        CapacityEvent event = eventService.resolveForCaller(eventId, callerUserId, tenantId);
        CapacityEventMember member = memberRepository.findByIdAndEventId(memberId, eventId)
                .orElseThrow(() -> new CapacityNotFoundException("event member", memberId));

        if (request.dateDebut().isAfter(request.dateFin())) {
            throw new CapacityValidationException("INVALID_DATE_RANGE", "dateDebut must not be after dateFin");
        }
        boolean fullyOutside = request.dateFin().isBefore(event.getStartDate())
                || request.dateDebut().isAfter(event.getEndDate());
        if (fullyOutside) {
            throw new CapacityValidationException("ABSENCE_OUTSIDE_EVENT", "Absence does not overlap the event period");
        }

        CapacityAbsence absence = new CapacityAbsence(member.getId(), request.dateDebut(), request.dateFin());
        absence = absenceRepository.save(absence);
        return new AbsenceResponse(absence.getId(), absence.getDateDebut(), absence.getDateFin());
    }

    /**
     * Deletes an absence.
     *
     * @param eventId      the event UUID
     * @param absenceId    the absence UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     */
    public void delete(final UUID eventId, final UUID absenceId, final Long callerUserId, final Long tenantId) {
        eventService.resolveForCaller(eventId, callerUserId, tenantId);
        CapacityAbsence absence = absenceRepository.findById(absenceId)
                .filter(candidate -> memberRepository.findById(candidate.getEventMemberId())
                        .map(CapacityEventMember::getEventId)
                        .filter(eventId::equals)
                        .isPresent())
                .orElseThrow(() -> new CapacityNotFoundException("absence", absenceId));
        absenceRepository.delete(absence);
    }
}
