package fr.pivot.collaboratif.meetops.booking;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.MalformedWindowEventException;
import fr.pivot.collaboratif.exception.MeetingConflictException;
import fr.pivot.collaboratif.exception.MeetingForbiddenException;
import fr.pivot.collaboratif.exception.MeetingNotFoundException;
import fr.pivot.collaboratif.exception.MeetingSlotInvalidException;
import fr.pivot.collaboratif.meeting.Meeting;
import fr.pivot.collaboratif.meeting.MeetingRepository;
import fr.pivot.collaboratif.meeting.MeetingStatus;
import fr.pivot.collaboratif.meetops.bestslot.BestSlotEngine;
import fr.pivot.collaboratif.meetops.bestslot.SlotCandidate;
import fr.pivot.collaboratif.meetops.booking.dto.MeetingBookingResponse;
import fr.pivot.collaboratif.meetops.booking.ws.MeetingRealtimePublisher;
import fr.pivot.collaboratif.meetops.bus.BookingConfirmedEvent;
import fr.pivot.collaboratif.meetops.bus.RescheduleRequestedEvent;
import fr.pivot.collaboratif.meetops.bus.WindowCreatedEvent;
import fr.pivot.collaboratif.meetops.bus.WindowDeletedEvent;
import fr.pivot.collaboratif.meetops.bus.WindowUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for the MeetOps roadmap booking flow (US12.4.1) — consuming {@code
 * roadmap.event.window.*}, ranking candidate slots, and organizer confirmation/manual adjustment.
 *
 * <p><strong>Organizer resolution (documented assumption).</strong> {@code
 * roadmap.event.window.created}'s contract carries no explicit organizer field (see the
 * pivot-docs AC table's payload shape: {@code {event_ref, project_ref, titre, participants[],
 * période, durée}}). By convention, the first entry of {@code participants[]} is treated as the
 * organizer, resolved to a platform user (best-effort, by e-mail, within the event's tenant —
 * {@link MeetingParticipantRepository#resolveUserIdByEmail}). If it does not resolve to a
 * registered account, {@code Meeting#getCreatedBy()} stays {@code null} and no caller can ever
 * confirm/adjust that meeting (a defensible, safe default — never silently picking an arbitrary
 * fallback organizer). This is flagged as an open point for EPIC-roadmap (US22.8.6) to resolve
 * properly once its own contract is finalized, not a bug in this US's own scope.
 */
@Service
public class BookingService {

    private static final Logger LOG = LoggerFactory.getLogger(BookingService.class);

    private final MeetingRepository meetingRepository;
    private final ProposedSlotRepository proposedSlotRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final BestSlotEngine bestSlotEngine;
    private final MeetingRealtimePublisher realtimePublisher;
    private final MeetingInvitationSender invitationSender;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Creates the service with its required dependencies.
     *
     * @param meetingRepository              meeting persistence
     * @param proposedSlotRepository         proposed-slot persistence
     * @param meetingParticipantRepository   participant persistence + e-mail resolution
     * @param bestSlotEngine                 candidate slot ranking
     * @param realtimePublisher              STOMP room push
     * @param invitationSender               confirmation invitation dispatch
     * @param eventPublisher                 in-process bus (see {@link WindowCreatedEvent}'s
     *                                       Javadoc)
     */
    public BookingService(
            final MeetingRepository meetingRepository,
            final ProposedSlotRepository proposedSlotRepository,
            final MeetingParticipantRepository meetingParticipantRepository,
            final BestSlotEngine bestSlotEngine,
            final MeetingRealtimePublisher realtimePublisher,
            final MeetingInvitationSender invitationSender,
            final ApplicationEventPublisher eventPublisher) {
        this.meetingRepository = meetingRepository;
        this.proposedSlotRepository = proposedSlotRepository;
        this.meetingParticipantRepository = meetingParticipantRepository;
        this.bestSlotEngine = bestSlotEngine;
        this.realtimePublisher = realtimePublisher;
        this.invitationSender = invitationSender;
        this.eventPublisher = eventPublisher;
    }

    // -------------------------------------------------------------------------
    // Bus consumption
    // -------------------------------------------------------------------------

    /**
     * Consumes {@code window.created} — creates a new {@code PRE_RESERVED} meeting with ranked
     * proposed slots, or is a no-op if a meeting already exists for {@code (tenantId, eventRef)}
     * (US12.4.1 idempotence AC — at-least-once redelivery never duplicates).
     *
     * @param event the event
     * @throws MalformedWindowEventException if the payload fails structural validation
     */
    @Transactional
    public void consumeWindowCreated(final WindowCreatedEvent event) {
        validate(
                event.eventRef(), event.title(), event.participants(), event.periodStart(), event.periodEnd(),
                event.durationMinutes());

        Optional<Meeting> existing = meetingRepository.findByTenantIdAndEventRef(event.tenantId(), event.eventRef());
        if (existing.isPresent()) {
            LOG.info("Idempotent window.created: tenant={} eventRef={} already pre-reserved",
                    event.tenantId(), event.eventRef());
            return;
        }

        Instant now = Instant.now();
        Long organizerId = resolveOrganizer(event.tenantId(), event.participants());
        Meeting meeting = Meeting.preReserve(
                event.tenantId(), event.title(), event.periodStart(), event.periodEnd(), event.durationMinutes(),
                event.eventRef(), event.projectRef(), organizerId, now);
        meeting = meetingRepository.save(meeting);

        saveParticipants(meeting, event.tenantId(), event.participants());
        List<ProposedSlot> slots = recomputeSlots(meeting, event.participants());

        realtimePublisher.publish(meeting, slots);
    }

    /**
     * Consumes {@code window.updated} — recomputes a non-{@code CONFIRMED} meeting's candidate
     * slots against the updated payload, or raises a reprogramming request if the meeting is
     * already {@code CONFIRMED} (US12.4.1 "cohérence window.updated/deleted"). A no-op if no
     * meeting exists yet for this {@code eventRef} (an update for an event this consumer never
     * saw a creation for — logged, not treated as an error).
     *
     * @param event the event
     * @throws MalformedWindowEventException if the payload fails structural validation
     */
    @Transactional
    public void consumeWindowUpdated(final WindowUpdatedEvent event) {
        validate(
                event.eventRef(), event.title(), event.participants(), event.periodStart(), event.periodEnd(),
                event.durationMinutes());

        Optional<Meeting> maybeMeeting = meetingRepository.findByTenantIdAndEventRef(event.tenantId(), event.eventRef());
        if (maybeMeeting.isEmpty()) {
            LOG.warn("window.updated for unknown eventRef: tenant={} eventRef={}", event.tenantId(), event.eventRef());
            return;
        }
        Meeting meeting = maybeMeeting.get();
        Instant now = Instant.now();

        if (meeting.getStatus() == MeetingStatus.CONFIRMED) {
            raiseRescheduleRequest(meeting, now);
            return;
        }

        meeting.applyWindowUpdate(
                event.title(), event.periodStart(), event.periodEnd(), event.durationMinutes(),
                event.projectRef(), now);
        meeting = meetingRepository.save(meeting);

        meetingParticipantRepository.deleteByMeetingId(meeting.getId());
        saveParticipants(meeting, event.tenantId(), event.participants());
        List<ProposedSlot> slots = recomputeSlots(meeting, event.participants());

        realtimePublisher.publish(meeting, slots);
    }

    /**
     * Consumes {@code window.deleted} — deletes a non-{@code CONFIRMED} meeting outright
     * (cascading its participants/proposed slots — "annulation", US12.4.1), or raises a
     * reprogramming request if it is already {@code CONFIRMED}. A no-op if no meeting exists for
     * this {@code eventRef}.
     *
     * @param event the event
     */
    @Transactional
    public void consumeWindowDeleted(final WindowDeletedEvent event) {
        Optional<Meeting> maybeMeeting = meetingRepository.findByTenantIdAndEventRef(event.tenantId(), event.eventRef());
        if (maybeMeeting.isEmpty()) {
            LOG.info("window.deleted for unknown eventRef: tenant={} eventRef={}", event.tenantId(), event.eventRef());
            return;
        }
        Meeting meeting = maybeMeeting.get();

        if (meeting.getStatus() == MeetingStatus.CONFIRMED) {
            raiseRescheduleRequest(meeting, Instant.now());
            return;
        }

        LOG.info("Cancelling PRE_RESERVED meeting on window.deleted: meeting={} eventRef={}",
                meeting.getId(), event.eventRef());
        realtimePublisher.publishCancelled(meeting.getId(), event.eventRef());
        meetingRepository.delete(meeting);
    }

    // -------------------------------------------------------------------------
    // Organizer-facing REST operations
    // -------------------------------------------------------------------------

    /**
     * Returns a meeting's current booking state (US12.4.1).
     *
     * @param meetingId the meeting id
     * @param principal the caller — supplies {@code tenantId}, never the path/body
     * @return the meeting + ranked proposed slots
     * @throws MeetingNotFoundException if the meeting does not exist within the caller's tenant
     */
    @Transactional(readOnly = true)
    public MeetingBookingResponse getById(final UUID meetingId, final CollaboratifRequestPrincipal principal) {
        Meeting meeting = resolveForTenant(meetingId, principal);
        return MeetingBookingResponse.from(meeting, proposedSlotRepository.findByMeetingIdOrderByRank(meetingId));
    }

    /**
     * Confirms a {@code PRE_RESERVED} meeting on the given proposed slot (US12.4.1 "Confirmation
     * → CONFIRMED + bus") — organizer-only.
     *
     * @param meetingId the meeting id
     * @param slotId    the retained {@code ProposedSlot} id
     * @param principal the caller — must be the meeting's organizer
     * @return the confirmed meeting
     * @throws MeetingNotFoundException  if the meeting does not exist within the caller's tenant
     * @throws MeetingForbiddenException if the caller is not the meeting's organizer
     * @throws MeetingConflictException  if the meeting is already {@code CONFIRMED} (or otherwise
     *                                   not in a confirmable state) — idempotent double-confirm
     *                                   guard, no re-publication/double invitation
     * @throws MeetingSlotInvalidException if {@code slotId} is absent from this meeting's own
     *                                     proposed slots
     */
    @Transactional
    public MeetingBookingResponse confirm(
            final UUID meetingId, final UUID slotId, final CollaboratifRequestPrincipal principal) {
        Meeting meeting = resolveForTenant(meetingId, principal);
        requireOrganizer(meeting, principal);

        if (meeting.getStatus() == MeetingStatus.CONFIRMED) {
            throw new MeetingConflictException("ALREADY_CONFIRMED", "Meeting is already confirmed");
        }
        if (meeting.getStatus() != MeetingStatus.PRE_RESERVED) {
            throw new MeetingConflictException("INVALID_STATUS", "Meeting is not pre-reserved");
        }
        ProposedSlot slot = proposedSlotRepository.findByIdAndMeetingId(slotId, meetingId)
                .orElseThrow(MeetingSlotInvalidException::new);

        Instant now = Instant.now();
        meeting.confirm(slot.getSlotStart(), now);
        meeting = meetingRepository.save(meeting);

        List<String> participantRefs = meetingParticipantRepository.findByMeetingId(meetingId).stream()
                .map(MeetingParticipant::getParticipantRef)
                .toList();
        invitationSender.sendInvitations(meeting.getId(), participantRefs);
        eventPublisher.publishEvent(new BookingConfirmedEvent(
                meeting.getTenantId(), meeting.getId(), meeting.getEventRef(), slot.getSlotStart(), slot.getSlotEnd()));

        List<ProposedSlot> slots = proposedSlotRepository.findByMeetingIdOrderByRank(meetingId);
        realtimePublisher.publish(meeting, slots);
        return MeetingBookingResponse.from(meeting, slots);
    }

    /**
     * Manually adjusts a proposed slot's boundaries while the meeting is still {@code
     * PRE_RESERVED} (US12.4.1 "Validation humaine" — organizer may adjust rather than confirm a
     * proposed slot as-is). Does not change the meeting's status.
     *
     * @param meetingId the meeting id
     * @param slotId    the {@code ProposedSlot} id to adjust
     * @param start     new slot start
     * @param end       new slot end
     * @param principal the caller — must be the meeting's organizer
     * @return the meeting, unchanged status, with the adjusted slot
     * @throws MeetingNotFoundException    if the meeting does not exist within the caller's tenant
     * @throws MeetingForbiddenException   if the caller is not the meeting's organizer
     * @throws MeetingConflictException    if the meeting is not {@code PRE_RESERVED}
     * @throws MeetingSlotInvalidException if {@code slotId} is absent from this meeting's own
     *                                     proposed slots
     * @throws IllegalArgumentException    if {@code end} is not strictly after {@code start}
     */
    @Transactional
    public MeetingBookingResponse adjustSlot(
            final UUID meetingId, final UUID slotId, final Instant start, final Instant end,
            final CollaboratifRequestPrincipal principal) {
        Meeting meeting = resolveForTenant(meetingId, principal);
        requireOrganizer(meeting, principal);

        if (meeting.getStatus() != MeetingStatus.PRE_RESERVED) {
            throw new MeetingConflictException("INVALID_STATUS", "Meeting is not pre-reserved");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be strictly after start");
        }
        ProposedSlot slot = proposedSlotRepository.findByIdAndMeetingId(slotId, meetingId)
                .orElseThrow(MeetingSlotInvalidException::new);

        slot.adjust(start, end);
        proposedSlotRepository.save(slot);

        List<ProposedSlot> slots = proposedSlotRepository.findByMeetingIdOrderByRank(meetingId);
        realtimePublisher.publish(meeting, slots);
        return MeetingBookingResponse.from(meeting, slots);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Meeting resolveForTenant(final UUID meetingId, final CollaboratifRequestPrincipal principal) {
        return meetingRepository.findByIdAndTenantId(meetingId, principal.tenantId())
                .orElseThrow(MeetingNotFoundException::new);
    }

    private void requireOrganizer(final Meeting meeting, final CollaboratifRequestPrincipal principal) {
        Long organizerId = meeting.getCreatedBy();
        if (organizerId == null || !organizerId.equals(principal.userId())) {
            throw new MeetingForbiddenException();
        }
    }

    private void raiseRescheduleRequest(final Meeting meeting, final Instant now) {
        meeting.requestReschedule(now);
        meetingRepository.save(meeting);
        eventPublisher.publishEvent(
                new RescheduleRequestedEvent(meeting.getTenantId(), meeting.getId(), meeting.getEventRef(), now));
        realtimePublisher.publish(meeting, proposedSlotRepository.findByMeetingIdOrderByRank(meeting.getId()));
        LOG.info("Reschedule requested for confirmed meeting={} eventRef={}", meeting.getId(), meeting.getEventRef());
    }

    private Long resolveOrganizer(final Long tenantId, final List<String> participants) {
        String organizerRef = participants.get(0);
        return meetingParticipantRepository.resolveUserIdByEmail(tenantId, organizerRef);
    }

    private void saveParticipants(final Meeting meeting, final Long tenantId, final List<String> participants) {
        for (String participantRef : participants) {
            Long resolvedUserId = meetingParticipantRepository.resolveUserIdByEmail(tenantId, participantRef);
            meetingParticipantRepository.save(new MeetingParticipant(meeting, participantRef, resolvedUserId));
        }
    }

    private List<ProposedSlot> recomputeSlots(final Meeting meeting, final List<String> participants) {
        proposedSlotRepository.deleteByMeetingId(meeting.getId());
        List<SlotCandidate> candidates = bestSlotEngine.rank(
                meeting.getBookingWindowStart(), meeting.getBookingWindowEnd(), meeting.getTotalDurationMinutes(),
                participants);

        Instant now = Instant.now();
        List<ProposedSlot> slots = new ArrayList<>();
        int rank = 1;
        for (SlotCandidate candidate : candidates) {
            slots.add(proposedSlotRepository.save(new ProposedSlot(
                    meeting, candidate.start(), candidate.end(), rank, candidate.hasConflict(),
                    candidate.conflictReason(), now)));
            rank++;
        }
        return slots;
    }

    private void validate(
            final String eventRef, final String title, final List<String> participants,
            final Instant periodStart, final Instant periodEnd, final Integer durationMinutes) {
        if (eventRef == null || eventRef.isBlank()) {
            throw new MalformedWindowEventException("eventRef is required");
        }
        if (title == null || title.isBlank()) {
            throw new MalformedWindowEventException("title is required");
        }
        if (participants == null || participants.isEmpty()) {
            throw new MalformedWindowEventException("participants must not be empty");
        }
        if (periodStart == null || periodEnd == null || !periodStart.isBefore(periodEnd)) {
            throw new MalformedWindowEventException("period must be non-empty with end after start");
        }
        if (durationMinutes == null || durationMinutes <= 0) {
            throw new MalformedWindowEventException("durationMinutes must be strictly positive");
        }
        long periodMinutes = Duration.between(periodStart, periodEnd).toMinutes();
        if (durationMinutes > periodMinutes) {
            throw new MalformedWindowEventException("durationMinutes exceeds the period length");
        }
    }
}
