package fr.pivot.collaboratif.meetops.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sends the confirmation invitation to a meeting's participants (US12.4.1). See {@link
 * MeetingInvitationsSentEvent}'s Javadoc for why this publishes an in-process event rather than
 * calling a real e-mail transport directly — that integration belongs to the notification module,
 * out of MeetOps' own scope this sprint.
 */
@Component
public class MeetingInvitationSender {

    private static final Logger LOG = LoggerFactory.getLogger(MeetingInvitationSender.class);

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Creates the sender with its required dependency.
     *
     * @param eventPublisher Spring's in-process application event bus
     */
    public MeetingInvitationSender(final ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Sends (publishes the intent to send) invitations to every given participant.
     *
     * @param meetingId    the confirmed meeting's id
     * @param participants participant refs (e-mails) to invite — never logged with any further
     *                     detail than the count, to stay clear of the RGPD "agrégat only" posture
     *                     this module holds elsewhere
     */
    public void sendInvitations(final UUID meetingId, final List<String> participants) {
        LOG.info("Sending {} meeting invitation(s) for meeting={}", participants.size(), meetingId);
        eventPublisher.publishEvent(new MeetingInvitationsSentEvent(meetingId, participants, Instant.now()));
    }
}
