package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.CapacitySummaryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for computing a capacity event's provisional summary (US11.1.2), including
 * PI Planning aggregation over its children (US11.3.1).
 */
@Service
@Transactional(readOnly = true)
public class CapacitySummaryService {

    private final CapacityEventService eventService;
    private final CapacityEventRepository eventRepository;
    private final CapacityEventMemberRepository memberRepository;
    private final CapacityAbsenceRepository absenceRepository;

    /**
     * Creates the service with its required dependencies.
     *
     * @param eventService       shared event access resolution
     * @param eventRepository    repository for event persistence
     * @param memberRepository   repository for roster member persistence
     * @param absenceRepository  repository for absence persistence
     */
    public CapacitySummaryService(
            final CapacityEventService eventService,
            final CapacityEventRepository eventRepository,
            final CapacityEventMemberRepository memberRepository,
            final CapacityAbsenceRepository absenceRepository) {
        this.eventService = eventService;
        this.eventRepository = eventRepository;
        this.memberRepository = memberRepository;
        this.absenceRepository = absenceRepository;
    }

    /**
     * Computes an event's provisional capacity summary — a leaf computation for {@code
     * SPRINT}/{@code RELEASE}/{@code CUSTOM} events, an aggregation over children for {@code
     * PI_PLANNING} events (US11.1.2/US11.3.1).
     *
     * @param eventId      the event UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the computed summary
     */
    public CapacitySummaryResponse getSummary(final UUID eventId, final Long callerUserId, final Long tenantId) {
        CapacityEvent event = eventService.resolveForCaller(eventId, callerUserId, tenantId);
        CapacityCalculator.Summary summary = event.getType() == CapacityEventType.PI_PLANNING
                ? summarizePiPlanning(event)
                : summarizeLeaf(event);
        return new CapacitySummaryResponse(
                summary.durationDays(),
                summary.workingDays(),
                summary.memberCount(),
                summary.totalAbsenceDays(),
                summary.netCapacityDays(),
                summary.netCapacityPoints(),
                summary.isProvisional());
    }

    /**
     * Computes a leaf event's own summary from its roster and their absences.
     *
     * @param event the leaf event
     * @return the computed summary
     */
    private CapacityCalculator.Summary summarizeLeaf(final CapacityEvent event) {
        List<CapacityEventMember> members = memberRepository.findAllByEventIdOrderByNameAsc(event.getId());
        List<UUID> memberIds = members.stream().map(CapacityEventMember::getId).toList();
        Map<UUID, List<CapacityAbsence>> absencesByMember = absenceRepository.findAllByEventMemberIdIn(memberIds)
                .stream()
                .collect(Collectors.groupingBy(CapacityAbsence::getEventMemberId));

        List<CapacityCalculator.MemberInput> inputs = members.stream()
                .map(member -> new CapacityCalculator.MemberInput(
                        member.isExcluded(),
                        member.getAvailabilityPercent(),
                        absencesByMember.getOrDefault(member.getId(), List.of()).stream()
                                .map(absence -> new CapacityCalculator.AbsenceRange(
                                        absence.getDateDebut(), absence.getDateFin()))
                                .toList()))
                .toList();

        return CapacityCalculator.summarize(event.getStartDate(), event.getEndDate(), inputs, event.getPointsPerDay());
    }

    /**
     * Computes a PI Planning event's aggregated summary from its children's own summaries.
     *
     * @param piEvent the PI Planning event
     * @return the aggregated summary
     */
    private CapacityCalculator.Summary summarizePiPlanning(final CapacityEvent piEvent) {
        List<CapacityEvent> children = eventRepository.findAllByParentEventIdOrderByStartDateAsc(piEvent.getId());
        List<CapacityCalculator.Summary> childSummaries = children.stream().map(this::summarizeLeaf).toList();
        return CapacityCalculator.aggregate(piEvent.getStartDate(), piEvent.getEndDate(), childSummaries);
    }
}
