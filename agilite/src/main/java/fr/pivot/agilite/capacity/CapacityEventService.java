package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.CreateEventRequest;
import fr.pivot.agilite.capacity.dto.EventRef;
import fr.pivot.agilite.capacity.dto.EventResponse;
import fr.pivot.agilite.capacity.dto.UpdateEventRequest;
import fr.pivot.agilite.exception.CapacityConflictException;
import fr.pivot.agilite.exception.CapacityValidationException;
import fr.pivot.agilite.team.TeamMembershipService;
import fr.pivot.agilite.team.dto.TeamMemberResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for capacity event CRUD and PI/Sprint hierarchy (US11.1.1/US11.3.1).
 */
@Service
@Transactional
public class CapacityEventService {

    /** Maximum name length, enforced alongside the bean-validation {@code @Size} annotation. */
    private static final int MAX_NAME_LENGTH = 120;

    private final CapacityEventRepository eventRepository;
    private final CapacityEventMemberRepository memberRepository;
    private final CapacityEventAccessService accessService;
    private final TeamMembershipService teamMembershipService;

    /**
     * Creates the service with its required dependencies.
     *
     * @param eventRepository        repository for event persistence
     * @param memberRepository       repository for roster member persistence
     * @param accessService          shared event access resolution (US11.1.1)
     * @param teamMembershipService  shared team-resolution/membership-check helper
     */
    public CapacityEventService(
            final CapacityEventRepository eventRepository,
            final CapacityEventMemberRepository memberRepository,
            final CapacityEventAccessService accessService,
            final TeamMembershipService teamMembershipService) {
        this.eventRepository = eventRepository;
        this.memberRepository = memberRepository;
        this.accessService = accessService;
        this.teamMembershipService = teamMembershipService;
    }

    /**
     * Creates a new capacity event, auto-seeding its roster from the owning team for
     * non-{@code PI_PLANNING} types (US11.1.1/US11.2.1).
     *
     * @param request      the creation request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the created event's response
     */
    public EventResponse create(final CreateEventRequest request, final Long callerUserId, final Long tenantId) {
        validateName(request.name());
        validateDateRange(request.startDate(), request.endDate());
        teamMembershipService.resolveTeamForCaller(request.teamId(), callerUserId, tenantId);

        UUID parentEventId = request.parentEventId();
        if (parentEventId != null) {
            CapacityEvent parent = accessService.resolveEventForCaller(parentEventId, callerUserId, tenantId);
            // Depth checked before type: a candidate that already has its own parent is rejected
            // as too-deep regardless of type (US11.3.1's "événement déjà parent" AC row) — a
            // parent-less, non-PI_PLANNING candidate falls through to the type check below.
            if (parent.getParentEventId() != null) {
                throw new CapacityValidationException("MAX_DEPTH_EXCEEDED", "Parent event already has a parent");
            }
            if (parent.getType() != CapacityEventType.PI_PLANNING) {
                throw new CapacityValidationException("INVALID_PARENT_EVENT", "Parent event must be PI_PLANNING");
            }
        }

        Instant now = Instant.now();
        CapacityEvent draft = new CapacityEvent(
                tenantId,
                request.teamId(),
                parentEventId,
                request.type(),
                request.name(),
                request.startDate(),
                request.endDate(),
                callerUserId,
                now);
        CapacityEvent event = eventRepository.save(draft);

        if (request.type() != CapacityEventType.PI_PLANNING) {
            List<TeamMemberResponse> teamMembers =
                    teamMembershipService.listMembers(request.teamId(), callerUserId, tenantId);
            List<CapacityEventMember> roster = teamMembers.stream()
                    .map(member -> new CapacityEventMember(event.getId(), member.id(), member.displayName()))
                    .toList();
            memberRepository.saveAll(roster);
        }

        return toResponse(event, callerUserId, tenantId);
    }

    /**
     * Returns a single event by id, if the caller has access, including its parent/children
     * summaries.
     *
     * @param eventId      the event UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the event's response
     */
    @Transactional(readOnly = true)
    public EventResponse findById(final UUID eventId, final Long callerUserId, final Long tenantId) {
        CapacityEvent event = accessService.resolveEventForCaller(eventId, callerUserId, tenantId);
        return toResponse(event, callerUserId, tenantId);
    }

    /**
     * Lists the events accessible to the caller within their tenant, optionally filtered by
     * {@code teamId}/{@code type}.
     *
     * @param teamId       optional team filter, or {@code null}
     * @param type         optional type filter, or {@code null}
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the accessible events, most recent {@code startDate} first
     */
    @Transactional(readOnly = true)
    public List<EventResponse> list(
            final Long teamId, final CapacityEventType type, final Long callerUserId, final Long tenantId) {
        return eventRepository.findAllByTenantIdOrderByStartDateDesc(tenantId).stream()
                .filter(event -> accessService.isAccessible(event, callerUserId, tenantId))
                .filter(event -> teamId == null || teamId.equals(event.getTeamId()))
                .filter(event -> type == null || type == event.getType())
                .map(event -> new EventResponse(
                        event.getId(),
                        event.getType(),
                        event.getName(),
                        event.getTeamId(),
                        event.getStartDate(),
                        event.getEndDate(),
                        event.getPointsPerDay(),
                        event.getCommittedPoints(),
                        event.getCompletedPoints(),
                        event.getCreatedBy(),
                        event.getCreatedAt(),
                        event.getUpdatedAt(),
                        null,
                        List.of()))
                .toList();
    }

    /**
     * Lists a PI Planning event's direct children (US11.3.1).
     *
     * @param piEventId    the PI Planning event's UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the children summaries, ordered by {@code startDate} ascending
     */
    @Transactional(readOnly = true)
    public List<EventRef> listChildren(final UUID piEventId, final Long callerUserId, final Long tenantId) {
        accessService.resolveEventForCaller(piEventId, callerUserId, tenantId);
        return eventRepository.findAllByParentEventIdOrderByStartDateAsc(piEventId).stream()
                .map(this::toRef)
                .toList();
    }

    /**
     * Updates an event's own fields.
     *
     * @param eventId      the event UUID
     * @param request      the update request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the updated event's response
     */
    public EventResponse update(
            final UUID eventId, final UpdateEventRequest request, final Long callerUserId, final Long tenantId) {
        CapacityEvent event = accessService.resolveEventForCaller(eventId, callerUserId, tenantId);
        if (request.name() != null) {
            validateName(request.name());
            event.setName(request.name());
        }
        LocalDateRange range = resolveEffectiveRange(event, request.startDate(), request.endDate());
        validateDateRange(range.startDate(), range.endDate());
        event.setStartDate(range.startDate());
        event.setEndDate(range.endDate());
        return toResponse(event, callerUserId, tenantId);
    }

    /**
     * Deletes an event created by the caller, cascading its roster/absences/burndown entries —
     * refused if it still has children.
     *
     * @param eventId      the event UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     */
    public void delete(final UUID eventId, final Long callerUserId, final Long tenantId) {
        CapacityEvent event = accessService.resolveEventForCaller(eventId, callerUserId, tenantId);
        if (eventRepository.countByParentEventId(eventId) > 0) {
            throw new CapacityConflictException("EVENT_HAS_CHILDREN", "Delete children before their PI Planning event");
        }
        eventRepository.delete(event);
    }

    /**
     * Resolves an event by id, tenant, and access — package-visible for sibling services
     * ({@code CapacityMemberService}, {@code CapacityAbsenceService}, etc.) that need the entity
     * without re-implementing access resolution.
     *
     * @param eventId      the event UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the resolved event
     */
    CapacityEvent resolveForCaller(final UUID eventId, final Long callerUserId, final Long tenantId) {
        return accessService.resolveEventForCaller(eventId, callerUserId, tenantId);
    }

    /**
     * Validates a name is non-blank and within the max length — service-layer validation
     * covering the same rule the bean-validation annotations already enforce on request DTOs,
     * shared by both create and update paths.
     *
     * @param name the candidate name
     */
    private void validateName(final String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new CapacityValidationException("INVALID_NAME", "Name must be 1-" + MAX_NAME_LENGTH + " characters");
        }
    }

    /**
     * Validates that a date range has a strictly-before start relative to its end.
     *
     * @param startDate the range start
     * @param endDate   the range end
     */
    private void validateDateRange(final LocalDate startDate, final LocalDate endDate) {
        if (!startDate.isBefore(endDate)) {
            throw new CapacityValidationException("INVALID_DATE_RANGE", "startDate must be before endDate");
        }
    }

    /**
     * Resolves the effective start/end dates after applying an optional partial update.
     *
     * @param event     the event being updated
     * @param startDate the requested new start date, or {@code null} to keep the current one
     * @param endDate   the requested new end date, or {@code null} to keep the current one
     * @return the effective range to validate and persist
     */
    private LocalDateRange resolveEffectiveRange(
            final CapacityEvent event, final LocalDate startDate, final LocalDate endDate) {
        return new LocalDateRange(
                startDate != null ? startDate : event.getStartDate(),
                endDate != null ? endDate : event.getEndDate());
    }

    private record LocalDateRange(LocalDate startDate, LocalDate endDate) {
    }

    /**
     * Builds a full response for an event, resolving its parent/children summaries.
     *
     * @param event        the event
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the full event response
     */
    private EventResponse toResponse(final CapacityEvent event, final Long callerUserId, final Long tenantId) {
        EventRef parent = null;
        if (event.getParentEventId() != null) {
            parent = eventRepository.findByIdAndTenantId(event.getParentEventId(), tenantId)
                    .map(this::toRef)
                    .orElse(null);
        }
        List<EventRef> children = eventRepository.findAllByParentEventIdOrderByStartDateAsc(event.getId()).stream()
                .map(this::toRef)
                .toList();
        return new EventResponse(
                event.getId(),
                event.getType(),
                event.getName(),
                event.getTeamId(),
                event.getStartDate(),
                event.getEndDate(),
                event.getPointsPerDay(),
                event.getCommittedPoints(),
                event.getCompletedPoints(),
                event.getCreatedBy(),
                event.getCreatedAt(),
                event.getUpdatedAt(),
                parent,
                children);
    }

    private EventRef toRef(final CapacityEvent event) {
        return new EventRef(event.getId(), event.getType(), event.getName(), event.getStartDate(), event.getEndDate());
    }
}
