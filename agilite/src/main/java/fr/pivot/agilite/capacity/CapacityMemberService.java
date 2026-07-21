package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.CapacityAbsenceRequest;
import fr.pivot.agilite.capacity.dto.CapacityAbsenceResponse;
import fr.pivot.agilite.capacity.dto.CapacityMemberRequest;
import fr.pivot.agilite.capacity.dto.CapacityMemberResponse;
import fr.pivot.agilite.capacity.exception.CapacityAbsenceNotFoundException;
import fr.pivot.agilite.capacity.exception.CapacityAccessDeniedException;
import fr.pivot.agilite.capacity.exception.CapacityEventMemberNotFoundException;
import fr.pivot.agilite.capacity.exception.CapacityEventNotFoundException;
import fr.pivot.agilite.capacity.exception.CapacityValidationException;
import fr.pivot.core.team.TeamMember;
import fr.pivot.core.team.TeamMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Business logic for managing a {@link CapacityEvent}'s members and their absences (F11.2).
 *
 * <p>Tenant/team-membership are validated directly against {@code
 * fr.pivot.core.team.TeamMemberRepository}, exported as-is by {@code pivot-core-starter} — same
 * inline-access pattern already used by {@code RetroSessionService} — no local duplication of the
 * {@code Team}/{@code TeamMember} entities.
 *
 * <p>Writes require the caller's {@link TeamMember#getRole()} to be {@link
 * TeamMember#ROLE_RESPONSABLE} or {@link TeamMember#ROLE_ADJOINT} (the OWNER/EDITOR-equivalent
 * roles); a caller whose membership role is the default {@link TeamMember#ROLE_MEMBRE}
 * (VIEWER-equivalent) is rejected with {@link CapacityAccessDeniedException}. A caller who is not
 * a member of the event's team at all, or whose tenant does not match, never reaches that check —
 * both collapse to a 404 to avoid confirming cross-tenant existence.
 */
@Service
@Transactional
public class CapacityMemberService {

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
    public CapacityMemberService(
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
     * Adds a new member to a capacity event.
     *
     * @param eventId  the owning event's identifier
     * @param request  the member's data
     * @param callerId the authenticated caller's user id
     * @param tenantId the authenticated caller's tenant id
     * @return the created member
     * @throws CapacityEventNotFoundException if the event does not exist, belongs to another
     *                                         tenant, or the caller is not a member of its team
     * @throws CapacityAccessDeniedException  if the caller lacks a write-capable role
     * @throws CapacityValidationException    if {@code quotite}/{@code focusFactor} are out of
     *                                         range
     */
    public CapacityMemberResponse addMember(
            final UUID eventId, final CapacityMemberRequest request,
            final Long callerId, final Long tenantId) {
        CapacityEvent event = eventRepository.findByIdAndTenantId(eventId, tenantId)
                .orElseThrow(() -> new CapacityEventNotFoundException(eventId));
        requireWriteAccess(event, callerId);
        validateQuotite(request.quotite());
        validateFocusFactor(request.focusFactor());

        CapacityEventMember member = new CapacityEventMember(
                event.getId(),
                request.teamMemberRef(),
                request.name(),
                request.role(),
                request.quotite(),
                request.position() != null ? request.position() : 0);
        member.setFocusFactor(request.focusFactor());
        member.setLocality(request.locality());
        if (request.excluded() != null) {
            member.setExcluded(request.excluded());
        }

        return CapacityMemberResponse.from(memberRepository.save(member));
    }

    /**
     * Updates an existing member.
     *
     * @param memberId the member's identifier
     * @param request  the member's new data
     * @param callerId the authenticated caller's user id
     * @param tenantId the authenticated caller's tenant id
     * @return the updated member
     * @throws CapacityEventMemberNotFoundException if the member does not exist, its event
     *                                               belongs to another tenant, or the caller is
     *                                               not a member of its team
     * @throws CapacityAccessDeniedException         if the caller lacks a write-capable role
     * @throws CapacityValidationException           if {@code quotite}/{@code focusFactor} are
     *                                                out of range
     */
    public CapacityMemberResponse updateMember(
            final UUID memberId, final CapacityMemberRequest request,
            final Long callerId, final Long tenantId) {
        CapacityEventMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new CapacityEventMemberNotFoundException(memberId));
        CapacityEvent event = eventRepository.findByIdAndTenantId(member.getEventId(), tenantId)
                .orElseThrow(() -> new CapacityEventMemberNotFoundException(memberId));
        requireWriteAccess(event, callerId, () -> new CapacityEventMemberNotFoundException(memberId));
        validateQuotite(request.quotite());
        validateFocusFactor(request.focusFactor());

        member.setName(request.name());
        member.setRole(request.role());
        member.setQuotite(request.quotite());
        member.setFocusFactor(request.focusFactor());
        member.setLocality(request.locality());
        if (request.excluded() != null) {
            member.setExcluded(request.excluded());
        }
        if (request.position() != null) {
            member.setPosition(request.position());
        }

        return CapacityMemberResponse.from(memberRepository.save(member));
    }

    /**
     * Removes a member from its event. The database cascades the deletion to its absences ({@code
     * ON DELETE CASCADE} on {@code agilite.capacity_absence.event_member_id}).
     *
     * @param memberId the member's identifier
     * @param callerId the authenticated caller's user id
     * @param tenantId the authenticated caller's tenant id
     * @throws CapacityEventMemberNotFoundException if the member does not exist, its event
     *                                               belongs to another tenant, or the caller is
     *                                               not a member of its team
     * @throws CapacityAccessDeniedException         if the caller lacks a write-capable role
     */
    public void deleteMember(final UUID memberId, final Long callerId, final Long tenantId) {
        CapacityEventMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new CapacityEventMemberNotFoundException(memberId));
        CapacityEvent event = eventRepository.findByIdAndTenantId(member.getEventId(), tenantId)
                .orElseThrow(() -> new CapacityEventMemberNotFoundException(memberId));
        requireWriteAccess(event, callerId, () -> new CapacityEventMemberNotFoundException(memberId));

        memberRepository.deleteById(memberId);
    }

    /**
     * Adds an absence to a member.
     *
     * @param memberId the owning member's identifier
     * @param request  the absence's data
     * @param callerId the authenticated caller's user id
     * @param tenantId the authenticated caller's tenant id
     * @return the created absence
     * @throws CapacityEventMemberNotFoundException if the member does not exist, its event
     *                                               belongs to another tenant, or the caller is
     *                                               not a member of its team
     * @throws CapacityAccessDeniedException         if the caller lacks a write-capable role
     * @throws CapacityValidationException           if the date range, absence window, or
     *                                                fraction are invalid
     */
    public CapacityAbsenceResponse addAbsence(
            final UUID memberId, final CapacityAbsenceRequest request,
            final Long callerId, final Long tenantId) {
        CapacityEventMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new CapacityEventMemberNotFoundException(memberId));
        CapacityEvent event = eventRepository.findByIdAndTenantId(member.getEventId(), tenantId)
                .orElseThrow(() -> new CapacityEventMemberNotFoundException(memberId));
        requireWriteAccess(event, callerId, () -> new CapacityEventMemberNotFoundException(memberId));

        if (request.endDate().isBefore(request.startDate())) {
            throw new CapacityValidationException(
                    "INVALID_DATE_RANGE", "Absence endDate must not be before startDate");
        }
        if (request.startDate().isBefore(event.getStartDate())
                || request.endDate().isAfter(event.getEndDate())) {
            throw new CapacityValidationException(
                    "ABSENCE_OUT_OF_RANGE", "Absence must fall within its event's date window");
        }
        double fraction = request.fraction();
        if (fraction != CapacityAbsence.FRACTION_FULL_DAY && fraction != CapacityAbsence.FRACTION_HALF_DAY) {
            throw new CapacityValidationException(
                    "INVALID_FRACTION", "Absence fraction must be 1 or 0.5");
        }
        String source = request.source() != null ? request.source() : CapacityAbsence.SOURCE_MANUAL;

        CapacityAbsence absence = new CapacityAbsence(
                member.getId(), request.startDate(), request.endDate(), fraction, source, Instant.now());

        return CapacityAbsenceResponse.from(absenceRepository.save(absence));
    }

    /**
     * Removes an absence.
     *
     * @param absenceId the absence's identifier
     * @param callerId  the authenticated caller's user id
     * @param tenantId  the authenticated caller's tenant id
     * @throws CapacityAbsenceNotFoundException if the absence does not exist, its event belongs
     *                                           to another tenant, or the caller is not a member
     *                                           of its team
     * @throws CapacityAccessDeniedException    if the caller lacks a write-capable role
     */
    public void deleteAbsence(final UUID absenceId, final Long callerId, final Long tenantId) {
        CapacityAbsence absence = absenceRepository.findById(absenceId)
                .orElseThrow(() -> new CapacityAbsenceNotFoundException(absenceId));
        CapacityEventMember member = memberRepository.findById(absence.getEventMemberId())
                .orElseThrow(() -> new CapacityAbsenceNotFoundException(absenceId));
        CapacityEvent event = eventRepository.findByIdAndTenantId(member.getEventId(), tenantId)
                .orElseThrow(() -> new CapacityAbsenceNotFoundException(absenceId));
        requireWriteAccess(event, callerId, () -> new CapacityAbsenceNotFoundException(absenceId));

        absenceRepository.deleteById(absenceId);
    }

    /**
     * Checks that the caller is a member of the event's team (404 collapse) and holds a
     * write-capable role (403 otherwise), for the {@code /events/{eventId}/members} entry point
     * where a non-member never needs a bespoke not-found type.
     *
     * @param event    the resolved, tenant-checked event
     * @param callerId the authenticated caller's user id
     * @throws CapacityEventNotFoundException if the caller is not a member of the event's team
     * @throws CapacityAccessDeniedException  if the caller lacks a write-capable role
     */
    private void requireWriteAccess(final CapacityEvent event, final Long callerId) {
        requireWriteAccess(event, callerId, () -> new CapacityEventNotFoundException(event.getId()));
    }

    /**
     * Checks that the caller is a member of the event's team (using {@code
     * notMemberException} for the 404 collapse) and holds a write-capable role ({@link
     * TeamMember#ROLE_RESPONSABLE} or {@link TeamMember#ROLE_ADJOINT}) — otherwise {@link
     * CapacityAccessDeniedException} (403).
     *
     * @param event              the resolved, tenant-checked event
     * @param callerId           the authenticated caller's user id
     * @param notMemberException supplies the 404 exception to throw if the caller is not a member
     *                           of the event's team
     */
    private void requireWriteAccess(
            final CapacityEvent event, final Long callerId,
            final Supplier<RuntimeException> notMemberException) {
        TeamMember membership = teamMemberRepository.findByTeamIdAndUserId(event.getTeamId(), callerId)
                .orElseThrow(notMemberException);
        String role = membership.getRole();
        if (!TeamMember.ROLE_RESPONSABLE.equals(role) && !TeamMember.ROLE_ADJOINT.equals(role)) {
            throw new CapacityAccessDeniedException(
                    "Caller's role does not grant write access to this capacity event");
        }
    }

    /**
     * Validates {@code quotite} is in {@code (0, 1]}.
     *
     * @param quotite the value to validate
     * @throws CapacityValidationException if out of range
     */
    private void validateQuotite(final double quotite) {
        if (quotite <= 0 || quotite > 1) {
            throw new CapacityValidationException("INVALID_QUOTITE", "quotite must be in (0, 1]");
        }
    }

    /**
     * Validates {@code focusFactor} is in {@code [0, 1]}, when provided.
     *
     * @param focusFactor the value to validate, or {@code null}
     * @throws CapacityValidationException if out of range
     */
    private void validateFocusFactor(final Double focusFactor) {
        if (focusFactor != null && (focusFactor < 0 || focusFactor > 1)) {
            throw new CapacityValidationException("FOCUS_OUT_OF_RANGE", "focusFactor must be in [0, 1]");
        }
    }
}
