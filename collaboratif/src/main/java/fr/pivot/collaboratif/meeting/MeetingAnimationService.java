package fr.pivot.collaboratif.meeting;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.MeetingConflictException;
import fr.pivot.collaboratif.exception.MeetingEmptyAgendaException;
import fr.pivot.collaboratif.meeting.dto.AddMeetingActionRequest;
import fr.pivot.collaboratif.meeting.dto.AgendaItemChangedEvent;
import fr.pivot.collaboratif.meeting.dto.MeetingActionAddedEvent;
import fr.pivot.collaboratif.meeting.dto.MeetingActionDto;
import fr.pivot.collaboratif.meeting.dto.MeetingEndedEvent;
import fr.pivot.collaboratif.meeting.dto.MeetingLiveStateDto;
import fr.pivot.collaboratif.meeting.dto.MeetingStartedEvent;
import fr.pivot.collaboratif.meeting.dto.TimerTickEvent;
import fr.pivot.collaboratif.meeting.ws.MeetingDestinations;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for meeting animation (US12.2.1) — start/agenda-next/end lifecycle, the 1 Hz
 * timer computation shared with {@code MeetingTimerScheduler}, resynchronisation ({@code GET
 * .../live}, AC-07), and in-meeting action capture (AC-08).
 *
 * <p>Every action-changing method resolves its {@link Meeting} through {@link
 * MeetingAccessService#resolveMeetingForOwnerOrAdmin} — tenant isolation first (404, AC-S1), then
 * owner-or-admin (403, AC-S2). {@link #getLive} instead uses {@link
 * MeetingAccessService#resolveMeetingForCaller} — any visible participant, not just the animator
 * (AC-07). Every timer field is computed from {@link #clock} against {@code
 * current_item_started_at}, never from a client-supplied value (AC-S4) — see {@link
 * MeetingTimerMath}.
 */
@Service
public class MeetingAnimationService {

    private final MeetingRepository meetingRepository;
    private final MeetingActionRepository actionRepository;
    private final MeetingAccessService accessService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    /**
     * Creates the service with its required dependencies.
     *
     * @param meetingRepository repository for meeting (and cascaded agenda item) persistence
     * @param actionRepository  repository for captured in-meeting actions
     * @param accessService     resolves meetings with tenant/authorization enforcement
     * @param messagingTemplate STOMP broadcaster
     * @param clock             the shared {@code meetOpsClock}, overridable in tests
     */
    public MeetingAnimationService(
            final MeetingRepository meetingRepository,
            final MeetingActionRepository actionRepository,
            final MeetingAccessService accessService,
            final SimpMessagingTemplate messagingTemplate,
            @Qualifier("meetOpsClock") final Clock clock) {
        this.meetingRepository = meetingRepository;
        this.actionRepository = actionRepository;
        this.accessService = accessService;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    /**
     * Starts a meeting (AC-01) — owner or {@code ROLE_ADMIN} only.
     *
     * @param meetingId the meeting's UUID
     * @param principal the caller
     * @throws MeetingConflictException     if the meeting is already {@code IN_PROGRESS} or
     *                                       {@code ENDED} (409, AC-E1)
     * @throws MeetingEmptyAgendaException  if the meeting has no agenda items (422, AC-E3)
     */
    @Transactional
    public void start(final UUID meetingId, final CollaboratifRequestPrincipal principal) {
        Meeting meeting = accessService.resolveMeetingForOwnerOrAdmin(meetingId, principal);
        if (meeting.getStatus() == MeetingStatus.IN_PROGRESS) {
            throw new MeetingConflictException("MEETING_ALREADY_IN_PROGRESS", "Meeting is already in progress");
        }
        if (meeting.getStatus() == MeetingStatus.ENDED) {
            throw new MeetingConflictException("MEETING_ALREADY_ENDED", "Meeting has already ended");
        }
        if (meeting.getAgendaItems().isEmpty()) {
            throw new MeetingEmptyAgendaException();
        }
        Instant now = clock.instant();
        AgendaItem first = meeting.getAgendaItems().get(0);
        meeting.start(first, now);
        meetingRepository.save(meeting);
        MeetingLiveStateDto state = liveState(meeting, now);
        messagingTemplate.convertAndSend(MeetingDestinations.topicFor(meetingId), new MeetingStartedEvent(state));
    }

    /**
     * Advances to the next agenda item, or ends the meeting if the current item was the last
     * (AC-03) — owner or {@code ROLE_ADMIN} only.
     *
     * @param meetingId the meeting's UUID
     * @param principal the caller
     * @throws MeetingConflictException if the meeting is not currently {@code IN_PROGRESS} (409,
     *                                   AC-E2)
     */
    @Transactional
    public void next(final UUID meetingId, final CollaboratifRequestPrincipal principal) {
        Meeting meeting = accessService.resolveMeetingForOwnerOrAdmin(meetingId, principal);
        requireInProgress(meeting);
        Instant now = clock.instant();
        AgendaItem current = currentItemOrThrow(meeting);
        Optional<AgendaItem> next = findNext(meeting, current);
        if (next.isPresent()) {
            meeting.advanceTo(current, next.get(), now);
            meetingRepository.save(meeting);
            broadcastAgendaChanged(meeting, next.get());
        } else {
            // Gate 1 decision (pivot-docs PR #317): advancing past the last item closes the
            // meeting, same as an explicit POST .../end.
            meeting.end(current, now);
            meetingRepository.save(meeting);
            messagingTemplate.convertAndSend(
                    MeetingDestinations.topicFor(meetingId), new MeetingEndedEvent(meetingId));
        }
    }

    /**
     * Ends a meeting (AC-06) — owner or {@code ROLE_ADMIN} only.
     *
     * @param meetingId the meeting's UUID
     * @param principal the caller
     * @throws MeetingConflictException if the meeting is not currently {@code IN_PROGRESS} (409,
     *                                   AC-E2)
     */
    @Transactional
    public void end(final UUID meetingId, final CollaboratifRequestPrincipal principal) {
        Meeting meeting = accessService.resolveMeetingForOwnerOrAdmin(meetingId, principal);
        requireInProgress(meeting);
        Instant now = clock.instant();
        AgendaItem current = meeting.getCurrentAgendaItem().orElse(null);
        meeting.end(current, now);
        meetingRepository.save(meeting);
        messagingTemplate.convertAndSend(MeetingDestinations.topicFor(meetingId), new MeetingEndedEvent(meetingId));
    }

    /**
     * Captures a minimal action during a live meeting (AC-08) — owner or {@code ROLE_ADMIN} only.
     * {@code label}/{@code dueDate} are already Bean-Validated at the controller boundary
     * (AC-E4); this method only enforces the meeting being animatable.
     *
     * @param meetingId the meeting's UUID
     * @param request   the action to capture
     * @param principal the caller
     * @return the created action
     * @throws MeetingConflictException if the meeting is not currently {@code IN_PROGRESS} (409,
     *                                   AC-E2)
     */
    @Transactional
    public MeetingActionDto addAction(
            final UUID meetingId, final AddMeetingActionRequest request, final CollaboratifRequestPrincipal principal) {
        Meeting meeting = accessService.resolveMeetingForOwnerOrAdmin(meetingId, principal);
        requireInProgress(meeting);
        UUID currentItemId = meeting.getCurrentAgendaItemId();
        Instant now = clock.instant();
        MeetingAction action = actionRepository.save(new MeetingAction(
                meeting.getTenantId(), meetingId, currentItemId, request.label(), request.ownerUserId(),
                request.dueDate(), now));
        MeetingActionDto dto = MeetingActionDto.from(action);
        messagingTemplate.convertAndSend(
                MeetingDestinations.topicFor(meetingId), new MeetingActionAddedEvent(dto));
        return dto;
    }

    /**
     * Returns the meeting's full live animation state for a resyncing/joining participant
     * (AC-07) — any visible participant (owner or team member), not just the animator.
     *
     * @param meetingId the meeting's UUID
     * @param principal the caller
     * @return the live state, with every timer field freshly computed server-side
     */
    @Transactional(readOnly = true)
    public MeetingLiveStateDto getLive(final UUID meetingId, final CollaboratifRequestPrincipal principal) {
        Meeting meeting = accessService.resolveMeetingForCaller(meetingId, principal);
        return liveState(meeting, clock.instant());
    }

    /**
     * Recomputes and broadcasts the 1 Hz {@code TIMER_TICK} for one {@code IN_PROGRESS} meeting,
     * and handles expiry — persisting the overtime flag at completion time and, when {@code
     * auto_advance} is enabled and the current item is not the last, auto-advancing (AC-02/
     * AC-04/AC-05). Called by {@code MeetingTimerScheduler} once per meeting id per tick, each
     * call in its own short-lived transaction (see {@link
     * MeetingRepository#findIdsByStatus}'s JavaDoc for why).
     *
     * <p>On the last item expiring, deliberately does nothing beyond broadcasting the tick — AC-05
     * explicitly requires "sur le dernier point expiré aucune clôture automatique n'a lieu",
     * unlike the MANUAL {@link #next} on the last item (which does close the meeting, per the
     * Gate 1 decision documented there). These are two different, deliberately divergent
     * behaviors for the same "no next item" situation — one is an explicit facilitator action,
     * the other a passive timeout.
     *
     * @param meetingId the meeting to tick
     */
    @Transactional
    public void tick(final UUID meetingId) {
        Meeting meeting = meetingRepository.findById(meetingId).orElse(null);
        if (meeting == null || meeting.getStatus() != MeetingStatus.IN_PROGRESS) {
            return;
        }
        AgendaItem current = meeting.getCurrentAgendaItem().orElse(null);
        if (current == null) {
            return;
        }
        Instant now = clock.instant();
        MeetingTimerMath.Snapshot snapshot = MeetingTimerMath.compute(current, now);
        messagingTemplate.convertAndSend(
                MeetingDestinations.topicFor(meetingId),
                new TimerTickEvent(
                        meetingId, current.getId(), snapshot.elapsedSeconds(), snapshot.remainingSeconds(),
                        snapshot.overtimeSeconds()));
        if (!snapshot.overtime() || !meeting.isAutoAdvance()) {
            return;
        }
        Optional<AgendaItem> next = findNext(meeting, current);
        if (next.isEmpty()) {
            // Last item expired with auto-advance on: stays in overtime, no auto-close (AC-05).
            return;
        }
        meeting.advanceTo(current, next.get(), now);
        meetingRepository.save(meeting);
        broadcastAgendaChanged(meeting, next.get());
    }

    private void requireInProgress(final Meeting meeting) {
        if (meeting.getStatus() != MeetingStatus.IN_PROGRESS) {
            throw new MeetingConflictException("MEETING_NOT_IN_PROGRESS", "Meeting is not in progress");
        }
    }

    private AgendaItem currentItemOrThrow(final Meeting meeting) {
        return meeting.getCurrentAgendaItem()
                .orElseThrow(() -> new MeetingConflictException(
                        "MEETING_NOT_IN_PROGRESS", "Meeting has no current agenda item"));
    }

    private Optional<AgendaItem> findNext(final Meeting meeting, final AgendaItem current) {
        List<AgendaItem> items = meeting.getAgendaItems();
        int nextPosition = current.getPosition() + 1;
        return items.stream().filter(item -> item.getPosition() == nextPosition).findFirst();
    }

    private void broadcastAgendaChanged(final Meeting meeting, final AgendaItem newCurrent) {
        messagingTemplate.convertAndSend(
                MeetingDestinations.topicFor(meeting.getId()),
                new AgendaItemChangedEvent(
                        meeting.getId(), newCurrent.getPosition(), meeting.getAgendaItems().size(),
                        newCurrent.getId()));
    }

    private MeetingLiveStateDto liveState(final Meeting meeting, final Instant now) {
        AgendaItem current = meeting.getCurrentAgendaItem().orElse(null);
        MeetingTimerMath.Snapshot snapshot = MeetingTimerMath.compute(current, now);
        return MeetingLiveStateDto.from(
                meeting, snapshot.elapsedSeconds(), snapshot.remainingSeconds(), snapshot.overtime(),
                snapshot.overtimeSeconds());
    }
}
