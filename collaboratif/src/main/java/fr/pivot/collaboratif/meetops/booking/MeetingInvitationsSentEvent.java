package fr.pivot.collaboratif.meetops.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Signal that invitations were sent to a meeting's participants on confirmation (US12.4.1 AC
 * "Confirmation → CONFIRMED + bus" — "l'invitation est envoyée aux participants"). Published via
 * {@code ApplicationEventPublisher} by {@link MeetingInvitationSender}, the observable boundary
 * this sprint owns for this AC — wiring an actual e-mail/notification transport is left to the
 * notification module (out of MeetOps' own scope), same posture as {@code
 * BoardMembershipNotificationRequestedEvent} in the whiteboard sub-module.
 *
 * @param meetingId     the confirmed meeting's id
 * @param participants  the participant refs (e-mails) invitations were sent to
 * @param sentAt        when invitations were sent
 */
public record MeetingInvitationsSentEvent(UUID meetingId, List<String> participants, Instant sentAt) {
}
