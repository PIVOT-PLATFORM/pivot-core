package fr.pivot.agilite.poker.ws;

import fr.pivot.agilite.poker.ticket.PokerTicket;
import fr.pivot.agilite.poker.ticket.PokerTicketRepository;
import fr.pivot.agilite.poker.ticket.PokerTicketStatus;
import fr.pivot.agilite.poker.vote.PokerVote;
import fr.pivot.agilite.poker.vote.PokerVoteRepository;
import fr.pivot.agilite.poker.ws.dto.RosterParticipant;
import fr.pivot.agilite.poker.ws.dto.RosterUpdatedEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds and broadcasts a room's live named roster (E09 — classic parity).
 *
 * <p>Composes the two sides of a participant's state that live in different stores — their
 * identity/role (Redis roster, {@link PokerParticipantRegistryService}) and whether they have
 * voted on the room's currently open ticket (JPA votes, {@link PokerVoteRepository}) — into a
 * single {@link RosterUpdatedEvent} sent to {@code /topic/agilite/poker/{roomId}}. Correlation is
 * by {@link PokerParticipantKey}, which both stores share; the resulting event never carries that
 * key nor any card value (masked-until-reveal is preserved — only the boolean "has voted" is
 * exposed while a ticket is open).
 *
 * <p>Called on every roster-visible change: a participant joins, a vote is recorded, or a new
 * ticket opens (which resets everyone to "not voted", since votes are per-ticket).
 */
@Service
public class PokerRosterService {

    private final PokerParticipantRegistryService registryService;
    private final PokerTicketRepository ticketRepository;
    private final PokerVoteRepository voteRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param registryService  the room roster store
     * @param ticketRepository resolves the room's currently open ticket
     * @param voteRepository   resolves who has voted on that ticket
     * @param messagingTemplate broadcasts the {@code ROSTER_UPDATED} event
     */
    public PokerRosterService(
            final PokerParticipantRegistryService registryService,
            final PokerTicketRepository ticketRepository,
            final PokerVoteRepository voteRepository,
            final SimpMessagingTemplate messagingTemplate) {
        this.registryService = registryService;
        this.ticketRepository = ticketRepository;
        this.voteRepository = voteRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Builds the room's current roster and broadcasts it as a {@code ROSTER_UPDATED} event.
     *
     * @param roomId the room whose roster to broadcast
     */
    public void broadcast(final UUID roomId) {
        Set<String> votedKeys = votedParticipantKeys(roomId);
        List<RosterParticipant> participants = registryService.roster(roomId).stream()
                .map(member -> new RosterParticipant(
                        member.name(), member.role(), votedKeys.contains(member.participantKey())))
                .toList();
        messagingTemplate.convertAndSend(
                PokerRoomDestinations.roomTopic(roomId),
                (Object) RosterUpdatedEvent.of(roomId, participants));
    }

    /**
     * Resolves the room's current roster as a {@code participantKey -> display name} map (E09 —
     * attributed reveal). Used at reveal/recap time to attribute an otherwise anonymous {@code
     * PokerVote} (which only stores the participant key, never a name) to the name the
     * participant actually joined under.
     *
     * <p>Merges on a duplicate key (defensive — {@link PokerParticipantRegistryService#roster}
     * already keys on participant key, so this can never actually happen) by keeping the first
     * entry, never throwing.
     *
     * @param roomId the room's identifier
     * @return the resolved map, empty if the room has no registered participant
     */
    public Map<String, String> namesByParticipantKey(final UUID roomId) {
        return registryService.roster(roomId).stream()
                .collect(Collectors.toMap(
                        PokerParticipantRegistryService.RosterMember::participantKey,
                        PokerParticipantRegistryService.RosterMember::name,
                        (first, second) -> first));
    }

    /**
     * Resolves the set of participant keys that have voted on the room's currently open ticket.
     *
     * @param roomId the room's identifier
     * @return the voting participants' keys, or an empty set when no ticket is open
     */
    private Set<String> votedParticipantKeys(final UUID roomId) {
        Optional<PokerTicket> activeTicket =
                ticketRepository.findByRoomIdAndStatus(roomId, PokerTicketStatus.VOTING);
        if (activeTicket.isEmpty()) {
            return Set.of();
        }
        return voteRepository.findByTicketId(activeTicket.get().getId()).stream()
                .map(PokerVote::getParticipantKey)
                .collect(Collectors.toSet());
    }
}
