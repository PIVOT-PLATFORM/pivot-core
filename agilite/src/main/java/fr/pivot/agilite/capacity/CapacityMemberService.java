package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.AbsenceResponse;
import fr.pivot.agilite.capacity.dto.MemberResponse;
import fr.pivot.agilite.capacity.dto.UpdateMemberRequest;
import fr.pivot.agilite.exception.CapacityNotFoundException;
import fr.pivot.agilite.exception.CapacityValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for consulting/adjusting a capacity event's already-auto-seeded roster
 * (US11.2.1).
 */
@Service
@Transactional
public class CapacityMemberService {

    private static final int MIN_AVAILABILITY_PERCENT = 10;
    private static final int MAX_AVAILABILITY_PERCENT = 100;
    private static final int MIN_FOCUS_FACTOR_PERCENT = 10;
    private static final int MAX_FOCUS_FACTOR_PERCENT = 100;

    private final CapacityEventService eventService;
    private final CapacityEventMemberRepository memberRepository;
    private final CapacityAbsenceRepository absenceRepository;

    /**
     * Creates the service with its required dependencies.
     *
     * @param eventService      shared event access resolution
     * @param memberRepository  repository for roster member persistence
     * @param absenceRepository repository for absence persistence
     */
    public CapacityMemberService(
            final CapacityEventService eventService,
            final CapacityEventMemberRepository memberRepository,
            final CapacityAbsenceRepository absenceRepository) {
        this.eventService = eventService;
        this.memberRepository = memberRepository;
        this.absenceRepository = absenceRepository;
    }

    /**
     * Lists an event's roster, each with their absences (US11.2.1/US11.2.2).
     *
     * @param eventId      the event UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the roster, ordered by name
     */
    @Transactional(readOnly = true)
    public List<MemberResponse> list(final UUID eventId, final Long callerUserId, final Long tenantId) {
        eventService.resolveForCaller(eventId, callerUserId, tenantId);
        List<CapacityEventMember> members = memberRepository.findAllByEventIdOrderByNameAsc(eventId);
        List<UUID> memberIds = members.stream().map(CapacityEventMember::getId).toList();
        Map<UUID, List<AbsenceResponse>> absencesByMember = absenceRepository.findAllByEventMemberIdIn(memberIds)
                .stream()
                .sorted((a, b) -> a.getDateDebut().compareTo(b.getDateDebut()))
                .collect(Collectors.groupingBy(
                        CapacityAbsence::getEventMemberId,
                        Collectors.mapping(
                                absence -> new AbsenceResponse(absence.getId(), absence.getDateDebut(), absence.getDateFin()),
                                Collectors.toList())));
        return members.stream()
                .map(member -> toResponse(member, absencesByMember.getOrDefault(member.getId(), List.of())))
                .toList();
    }

    /**
     * Adjusts a roster member's availability/exclusion.
     *
     * @param eventId      the event UUID
     * @param memberId     the roster member UUID
     * @param request      the update request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the updated member's response
     */
    public MemberResponse updateMember(
            final UUID eventId,
            final UUID memberId,
            final UpdateMemberRequest request,
            final Long callerUserId,
            final Long tenantId) {
        eventService.resolveForCaller(eventId, callerUserId, tenantId);
        CapacityEventMember member = memberRepository.findByIdAndEventId(memberId, eventId)
                .orElseThrow(() -> new CapacityNotFoundException("event member", memberId));
        if (request.availabilityPercent() != null) {
            int value = request.availabilityPercent();
            if (value < MIN_AVAILABILITY_PERCENT || value > MAX_AVAILABILITY_PERCENT) {
                throw new CapacityValidationException(
                        "INVALID_AVAILABILITY",
                        "availabilityPercent must be between " + MIN_AVAILABILITY_PERCENT + " and " + MAX_AVAILABILITY_PERCENT);
            }
            member.setAvailabilityPercent(value);
        }
        if (request.excluded() != null) {
            member.setExcluded(request.excluded());
        }
        if (request.focusFactorPercent() != null) {
            int value = request.focusFactorPercent();
            if (value < MIN_FOCUS_FACTOR_PERCENT || value > MAX_FOCUS_FACTOR_PERCENT) {
                throw new CapacityValidationException(
                        "INVALID_FOCUS_FACTOR",
                        "focusFactorPercent must be between " + MIN_FOCUS_FACTOR_PERCENT + " and " + MAX_FOCUS_FACTOR_PERCENT);
            }
            member.setFocusFactorPercent(value);
        }
        List<AbsenceResponse> absences = absenceRepository.findAllByEventMemberIdOrderByDateDebutAsc(memberId).stream()
                .map(absence -> new AbsenceResponse(absence.getId(), absence.getDateDebut(), absence.getDateFin()))
                .toList();
        return toResponse(member, absences);
    }

    private MemberResponse toResponse(final CapacityEventMember member, final List<AbsenceResponse> absences) {
        return new MemberResponse(
                member.getId(), member.getTeamMemberId(), member.getName(), member.getAvailabilityPercent(),
                member.isExcluded(), absences, member.getFocusFactorPercent());
    }
}
