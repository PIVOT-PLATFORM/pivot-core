package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.CapacityEventChildResponse;
import fr.pivot.agilite.capacity.dto.CapacityEventRequest;
import fr.pivot.agilite.capacity.dto.CapacityEventResponse;
import fr.pivot.agilite.capacity.exception.CapacityAccessDeniedException;
import fr.pivot.agilite.capacity.exception.CapacityEventNotFoundException;
import fr.pivot.agilite.capacity.exception.CapacityValidationException;
import fr.pivot.core.team.TeamMember;
import fr.pivot.core.team.TeamMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for capacity event CRUD and two-level PI/sprint hierarchy (E11 — F11.1 events
 * CRUD + F11.3 hierarchy).
 *
 * <p><strong>Access.</strong> Mirrors {@code fr.pivot.agilite.retro.session.RetroSessionService}'s
 * canonical inline pattern: an event is resolved via {@link
 * CapacityEventRepository#findByIdAndTenantId}, then the caller's membership of the event's team
 * is checked directly against {@code fr.pivot.core.team.TeamMemberRepository} — a non-member (or
 * a caller from another tenant, already excluded by the tenant-scoped lookup) is rejected with
 * {@link CapacityEventNotFoundException} (404), never confirming the event's existence. A member
 * may always read; write operations (create/update/delete) additionally require the member's
 * {@link TeamMember#getRole()} to be {@link TeamMember#ROLE_RESPONSABLE} or {@link
 * TeamMember#ROLE_ADJOINT} — {@link TeamMember#ROLE_MEMBRE} is rejected with {@link
 * CapacityAccessDeniedException} (403). {@code TeamMember} has no {@code OWNER}/{@code
 * EDITOR}/{@code VIEWER} role model (its {@code role} column is an anticipated, currently
 * unenforced ADR-027 string with {@code RESPONSABLE}/{@code ADJOINT}/{@code MEMBRE} values); this
 * service maps {@code RESPONSABLE}/{@code ADJOINT} to the "may write" side and {@code MEMBRE} to
 * the "read only" side, the closest equivalent available.
 *
 * <p><strong>Mutability.</strong> {@link CapacityEvent} (read-only for this slice) exposes no
 * setter for {@code type}/{@code name}/{@code startDate}/{@code endDate}/{@code workingDays} —
 * those are structural fields fixed at creation and therefore never changed by {@link
 * #update(UUID, CapacityEventRequest, Long, Long)}, even though the shared {@link
 * CapacityEventRequest} DTO still carries them (required at creation). Only {@code status},
 * {@code parentId}, {@code maturityLevel}, {@code focusFactor}, {@code margeSecurite}, {@code
 * pointsPerDay}, {@code committedPoints}, and {@code notes} are mutable after creation.
 */
@Service
@Transactional
public class CapacityEventService {

    /** Default working days (Monday-Friday, {@code 0} = Sunday .. {@code 6} = Saturday). */
    private static final Integer[] DEFAULT_WORKING_DAYS = {1, 2, 3, 4, 5};

    private final CapacityEventRepository eventRepository;
    private final TeamMemberRepository teamMemberRepository;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param eventRepository      capacity event persistence
     * @param teamMemberRepository {@code pivot-core-starter}'s team membership persistence
     */
    public CapacityEventService(
            final CapacityEventRepository eventRepository, final TeamMemberRepository teamMemberRepository) {
        this.eventRepository = eventRepository;
        this.teamMemberRepository = teamMemberRepository;
    }

    /**
     * Creates a new capacity event.
     *
     * @param request  the validated creation request
     * @param callerId the authenticated caller's {@code public.users.id}
     * @param tenantId the authenticated caller's {@code public.tenants.id}
     * @return the created event
     * @throws CapacityValidationException  if {@code teamId} is missing, the date range is
     *                                       invalid, {@code focusFactor}/{@code margeSecurite}
     *                                       are out of range, or the hierarchy rules are violated
     * @throws CapacityAccessDeniedException if the caller is not a member of {@code teamId}, or
     *                                       is a member without write privileges
     */
    public CapacityEventResponse create(
            final CapacityEventRequest request, final Long callerId, final Long tenantId) {
        if (request.teamId() == null) {
            throw new CapacityValidationException("INVALID_TEAM", "teamId is required");
        }
        requireWriteMembership(request.teamId(), callerId);

        validateDateRange(request.startDate(), request.endDate());
        validateFocusFactor(request.focusFactor());
        validateMargeSecurite(request.margeSecurite());
        validateParent(request.parentId(), null, request.teamId(), tenantId);

        Integer[] workingDays = request.workingDays() != null && request.workingDays().length > 0
                ? request.workingDays()
                : DEFAULT_WORKING_DAYS;

        CapacityEvent event = new CapacityEvent(
                tenantId, request.teamId(), request.type(), request.name(),
                request.startDate(), request.endDate(), workingDays);
        event.setParentId(request.parentId());
        event.setMaturityLevel(request.maturityLevel());
        event.setFocusFactor(request.focusFactor());
        event.setMargeSecurite(request.margeSecurite());
        event.setPointsPerDay(request.pointsPerDay());
        event.setCommittedPoints(request.committedPoints());
        event.setNotes(request.notes());
        if (request.status() != null) {
            event.setStatus(request.status());
        }

        return CapacityEventResponse.from(eventRepository.save(event));
    }

    /**
     * Lists the capacity events of every team the caller belongs to, optionally filtered.
     *
     * @param teamId   restricts the listing to a single team; if the caller is not a member of
     *                 it, an empty list is returned (never leaks the team's existence)
     * @param type     restricts the listing to a single event type, or {@code null} for all
     * @param status   restricts the listing to a single lifecycle status, or {@code null} for all
     * @param callerId the authenticated caller's {@code public.users.id}
     * @param tenantId the authenticated caller's {@code public.tenants.id}
     * @return the matching events, in no particular order
     */
    @Transactional(readOnly = true)
    public List<CapacityEventResponse> list(
            final Long teamId, final CapacityEventType type, final CapacityEventStatus status,
            final Long callerId, final Long tenantId) {
        List<Long> memberTeamIds = teamMemberRepository.findAllByUserId(callerId).stream()
                .map(TeamMember::getTeamId)
                .toList();

        if (teamId != null) {
            memberTeamIds = memberTeamIds.contains(teamId) ? List.of(teamId) : List.of();
        }

        return memberTeamIds.stream()
                .flatMap(id -> eventRepository.findByTeamIdAndTenantId(id, tenantId).stream())
                .filter(event -> type == null || event.getType() == type)
                .filter(event -> status == null || event.getStatus() == status)
                .map(CapacityEventResponse::from)
                .toList();
    }

    /**
     * Returns a single capacity event.
     *
     * @param id       the event UUID from the path
     * @param callerId the authenticated caller's {@code public.users.id}
     * @param tenantId the authenticated caller's {@code public.tenants.id}
     * @return the event detail
     * @throws CapacityEventNotFoundException if the event does not exist, belongs to another
     *                                         tenant, or the caller is not a member of its team
     */
    @Transactional(readOnly = true)
    public CapacityEventResponse findById(final UUID id, final Long callerId, final Long tenantId) {
        return CapacityEventResponse.from(resolveForRead(id, tenantId, callerId));
    }

    /**
     * Updates a capacity event's mutable fields — see this class's Javadoc for exactly which
     * fields are mutable ({@code type}/{@code name}/{@code startDate}/{@code endDate}/{@code
     * workingDays} never change after creation).
     *
     * @param id       the event UUID from the path
     * @param request  the validated update request
     * @param callerId the authenticated caller's {@code public.users.id}
     * @param tenantId the authenticated caller's {@code public.tenants.id}
     * @return the updated event
     * @throws CapacityEventNotFoundException if the event does not exist, belongs to another
     *                                         tenant, or the caller is not a member of its team
     * @throws CapacityAccessDeniedException  if the caller is a member without write privileges
     * @throws CapacityValidationException    if {@code focusFactor}/{@code margeSecurite} are out
     *                                         of range, or the hierarchy rules are violated
     */
    public CapacityEventResponse update(
            final UUID id, final CapacityEventRequest request, final Long callerId, final Long tenantId) {
        CapacityEvent event = resolveForWrite(id, tenantId, callerId);

        validateFocusFactor(request.focusFactor());
        validateMargeSecurite(request.margeSecurite());
        validateParent(request.parentId(), event.getId(), event.getTeamId(), tenantId);

        event.setParentId(request.parentId());
        event.setMaturityLevel(request.maturityLevel());
        event.setFocusFactor(request.focusFactor());
        event.setMargeSecurite(request.margeSecurite());
        event.setPointsPerDay(request.pointsPerDay());
        event.setCommittedPoints(request.committedPoints());
        event.setNotes(request.notes());
        if (request.status() != null) {
            event.setStatus(request.status());
        }

        return CapacityEventResponse.from(eventRepository.save(event));
    }

    /**
     * Permanently deletes a capacity event (its children, if any, are detached/removed by the
     * database FK behavior — no in-service cascade logic).
     *
     * @param id       the event UUID from the path
     * @param callerId the authenticated caller's {@code public.users.id}
     * @param tenantId the authenticated caller's {@code public.tenants.id}
     * @throws CapacityEventNotFoundException if the event does not exist, belongs to another
     *                                         tenant, or the caller is not a member of its team
     * @throws CapacityAccessDeniedException  if the caller is a member without write privileges
     */
    public void delete(final UUID id, final Long callerId, final Long tenantId) {
        CapacityEvent event = resolveForWrite(id, tenantId, callerId);
        eventRepository.delete(event);
    }

    /**
     * Lists a PI's direct children (e.g. its sprints).
     *
     * @param piId     the parent event's UUID from the path
     * @param callerId the authenticated caller's {@code public.users.id}
     * @param tenantId the authenticated caller's {@code public.tenants.id}
     * @return the parent's direct children, in no particular order
     * @throws CapacityEventNotFoundException if the parent event does not exist, belongs to
     *                                         another tenant, or the caller is not a member of
     *                                         its team
     */
    @Transactional(readOnly = true)
    public List<CapacityEventChildResponse> children(final UUID piId, final Long callerId, final Long tenantId) {
        resolveForRead(piId, tenantId, callerId);
        return eventRepository.findByParentIdAndTenantId(piId, tenantId).stream()
                .map(CapacityEventChildResponse::from)
                .toList();
    }

    /**
     * Resolves an event by id and tenant, then verifies the caller is a member of its team.
     *
     * @param id       the event id
     * @param tenantId the caller's tenant id
     * @param callerId the caller's user id
     * @return the resolved event
     * @throws CapacityEventNotFoundException if the event does not exist, belongs to another
     *                                         tenant, or the caller is not a member of its team
     */
    private CapacityEvent resolveForRead(final UUID id, final Long tenantId, final Long callerId) {
        CapacityEvent event = eventRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new CapacityEventNotFoundException(id));
        findMembershipOrNotFound(event.getTeamId(), callerId, id);
        return event;
    }

    /**
     * Resolves an event by id and tenant, verifies the caller is a member of its team, then
     * verifies the member's role grants write access.
     *
     * @param id       the event id
     * @param tenantId the caller's tenant id
     * @param callerId the caller's user id
     * @return the resolved event
     * @throws CapacityEventNotFoundException if the event does not exist, belongs to another
     *                                         tenant, or the caller is not a member of its team
     * @throws CapacityAccessDeniedException  if the caller is a member without write privileges
     */
    private CapacityEvent resolveForWrite(final UUID id, final Long tenantId, final Long callerId) {
        CapacityEvent event = eventRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new CapacityEventNotFoundException(id));
        TeamMember member = findMembershipOrNotFound(event.getTeamId(), callerId, id);
        requireWriteRole(member);
        return event;
    }

    /**
     * Verifies the caller is a member of {@code teamId} with write privileges — used by {@link
     * #create(CapacityEventRequest, Long, Long)}, which has no existing event to resolve access
     * through.
     *
     * @param teamId   the target team id from the create request
     * @param callerId the caller's user id
     * @throws CapacityAccessDeniedException if the caller is not a member of {@code teamId}, or
     *                                        is a member without write privileges
     */
    private void requireWriteMembership(final Long teamId, final Long callerId) {
        TeamMember member = teamMemberRepository.findByTeamIdAndUserId(teamId, callerId)
                .orElseThrow(() -> new CapacityAccessDeniedException(
                        "Caller is not a member of team " + teamId));
        requireWriteRole(member);
    }

    /**
     * Finds the caller's membership of {@code teamId}, or throws the 404 event-not-found
     * exception — a non-member never learns whether the event exists.
     *
     * @param teamId   the event's owning team id
     * @param callerId the caller's user id
     * @param eventId  the event id, for the exception message
     * @return the caller's membership
     * @throws CapacityEventNotFoundException if the caller is not a member of {@code teamId}
     */
    private TeamMember findMembershipOrNotFound(final Long teamId, final Long callerId, final UUID eventId) {
        return teamMemberRepository.findByTeamIdAndUserId(teamId, callerId)
                .orElseThrow(() -> new CapacityEventNotFoundException(eventId));
    }

    /**
     * Verifies a team member's role grants write access to capacity events — {@link
     * TeamMember#ROLE_RESPONSABLE} and {@link TeamMember#ROLE_ADJOINT} may write, {@link
     * TeamMember#ROLE_MEMBRE} may only read.
     *
     * @param member the caller's team membership
     * @throws CapacityAccessDeniedException if {@code member}'s role is {@link
     *                                        TeamMember#ROLE_MEMBRE}
     */
    private void requireWriteRole(final TeamMember member) {
        if (TeamMember.ROLE_MEMBRE.equals(member.getRole())) {
            throw new CapacityAccessDeniedException(
                    "Role " + member.getRole() + " cannot modify capacity events "
                            + "(requires " + TeamMember.ROLE_RESPONSABLE + " or " + TeamMember.ROLE_ADJOINT + ")");
        }
    }

    /**
     * Validates the {@code endDate >= startDate} rule.
     *
     * @param startDate the event's first day
     * @param endDate   the event's last day
     * @throws CapacityValidationException with code {@code INVALID_DATE_RANGE} if {@code endDate}
     *                                      is before {@code startDate}
     */
    private void validateDateRange(final LocalDate startDate, final LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new CapacityValidationException("INVALID_DATE_RANGE", "endDate must not be before startDate");
        }
    }

    /**
     * Validates a focus factor is within {@code [0, 1]} when provided.
     *
     * @param focusFactor the candidate value, may be {@code null}
     * @throws CapacityValidationException with code {@code FOCUS_OUT_OF_RANGE} if out of range
     */
    private void validateFocusFactor(final Double focusFactor) {
        if (focusFactor != null && (focusFactor < 0 || focusFactor > 1)) {
            throw new CapacityValidationException("FOCUS_OUT_OF_RANGE", "focusFactor must be within [0,1]");
        }
    }

    /**
     * Validates a safety margin is within {@code [0, 1]} when provided.
     *
     * @param margeSecurite the candidate value, may be {@code null}
     * @throws CapacityValidationException with code {@code MARGE_OUT_OF_RANGE} if out of range
     */
    private void validateMargeSecurite(final Double margeSecurite) {
        if (margeSecurite != null && (margeSecurite < 0 || margeSecurite > 1)) {
            throw new CapacityValidationException("MARGE_OUT_OF_RANGE", "margeSecurite must be within [0,1]");
        }
    }

    /**
     * Validates the two-level hierarchy rules (F11.3) for a candidate {@code parentId}.
     *
     * @param parentId the candidate parent id, or {@code null} for a root-level event
     * @param selfId   the event being created/updated's own id, or {@code null} on create
     * @param teamId   the event's owning team id, the parent must belong to the same team
     * @param tenantId the caller's tenant id, used to resolve the parent
     * @throws CapacityValidationException with code {@code INVALID_PARENT} if self-parenting is
     *                                      attempted, the parent does not exist, or the parent
     *                                      belongs to a different team; with code {@code
     *                                      HIERARCHY_TOO_DEEP} if the parent itself has a parent;
     *                                      with code {@code INVALID_PARENT_TYPE} if the parent is
     *                                      not a {@link CapacityEventType#PI_PLANNING} event
     */
    private void validateParent(final UUID parentId, final UUID selfId, final Long teamId, final Long tenantId) {
        if (parentId == null) {
            return;
        }
        if (parentId.equals(selfId)) {
            throw new CapacityValidationException("INVALID_PARENT", "An event cannot be its own parent");
        }
        CapacityEvent parent = eventRepository.findByIdAndTenantId(parentId, tenantId)
                .orElseThrow(() -> new CapacityValidationException(
                        "INVALID_PARENT", "Parent event not found: " + parentId));
        if (!parent.getTeamId().equals(teamId)) {
            throw new CapacityValidationException("INVALID_PARENT", "Parent event must belong to the same team");
        }
        if (parent.getParentId() != null) {
            throw new CapacityValidationException("HIERARCHY_TOO_DEEP", "Hierarchy depth is limited to 2 levels");
        }
        if (parent.getType() != CapacityEventType.PI_PLANNING) {
            throw new CapacityValidationException("INVALID_PARENT_TYPE", "Parent event must be a PI_PLANNING event");
        }
    }
}
