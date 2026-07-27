package fr.pivot.collaboratif.meetops.booking.ws;

import fr.pivot.collaboratif.meeting.Meeting;
import fr.pivot.collaboratif.meetops.booking.ProposedSlot;
import fr.pivot.collaboratif.meetops.booking.dto.MeetingBookingResponse;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pushes booking-flow state changes to {@code /topic/collaboratif/meeting/{meetingId}} (US12.4.1
 * "Temps réel" AC) — mirrors {@code ModuleSessionService}'s {@code
 * messagingTemplate.convertAndSend(SessionDestinations.topicFor(...), ...)} pattern.
 *
 * <p>Pushes the same {@link MeetingBookingResponse} shape the REST surface returns, so a
 * subscribed client's {@code aria-live} region (US12.4.1 A11y AC) can announce the update from
 * data it already knows how to render.
 */
@Component
public class MeetingRealtimePublisher {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Creates the publisher with its required dependency.
     *
     * @param messagingTemplate the STOMP broker messaging template
     */
    public MeetingRealtimePublisher(final SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Broadcasts the current state of a meeting and its proposed slots to its room.
     *
     * @param meeting the meeting, in its post-mutation state
     * @param slots   the meeting's proposed slots, already rank-ordered
     */
    public void publish(final Meeting meeting, final List<ProposedSlot> slots) {
        messagingTemplate.convertAndSend(
                MeetingDestinations.topicFor(meeting.getId()), MeetingBookingResponse.from(meeting, slots));
    }
}
