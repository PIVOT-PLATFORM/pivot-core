package fr.pivot.agilite.poker.ticket;

import fr.pivot.agilite.poker.PokerCardDeck;
import fr.pivot.agilite.poker.PokerRoom;
import fr.pivot.agilite.poker.PokerRoomRepository;
import fr.pivot.agilite.poker.exception.ActiveTicketExistsException;
import fr.pivot.agilite.poker.exception.TicketAlreadyFinalizedException;
import fr.pivot.agilite.poker.exception.TicketAlreadyRevealedException;
import fr.pivot.agilite.poker.exception.TicketFacilitatorOnlyException;
import fr.pivot.agilite.poker.exception.TicketNotRevealedException;
import fr.pivot.agilite.poker.exception.RoomNotFoundException;
import fr.pivot.agilite.poker.exception.TicketNotFoundException;
import fr.pivot.agilite.poker.ticket.dto.AttributedVoteResponse;
import fr.pivot.agilite.poker.ticket.dto.ConsensusResponse;
import fr.pivot.agilite.poker.ticket.dto.RecapResponse;
import fr.pivot.agilite.poker.ticket.dto.RevealResponse;
import fr.pivot.agilite.poker.ticket.dto.TicketCreatedEvent;
import fr.pivot.agilite.poker.ticket.dto.TicketFinalizedEvent;
import fr.pivot.agilite.poker.ticket.dto.TicketFinalizedResponse;
import fr.pivot.agilite.poker.ticket.dto.TicketRecapEntry;
import fr.pivot.agilite.poker.ticket.dto.TicketResetEvent;
import fr.pivot.agilite.poker.ticket.dto.TicketResetResponse;
import fr.pivot.agilite.poker.ticket.dto.TicketResponse;
import fr.pivot.agilite.poker.ticket.dto.VotesRevealedEvent;
import fr.pivot.agilite.poker.vote.PokerVote;
import fr.pivot.agilite.poker.vote.PokerVoteRepository;
import fr.pivot.agilite.poker.ws.PokerRoomDestinations;
import fr.pivot.agilite.poker.ws.PokerRosterService;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for planning poker ticket creation, current-ticket lookup (US09.2.1),
 * revelation/consensus calculation (US09.2.2), attributed reveal + end-of-session recap
 * (E09 — classic parity), and reset/finalization of a ticket's estimate (US09.2.3).
 */
@Service
public class PokerTicketService {

    /**
     * Roster display name substituted for a vote whose participant key no longer resolves in the
     * room's live roster at reveal/recap time (e.g. an anonymous guest session that expired
     * between voting and reveal) — never a blank/null name.
     */
    private static final String UNKNOWN_PARTICIPANT_NAME = "Participant";

    private final PokerTicketRepository ticketRepository;
    private final PokerRoomRepository roomRepository;
    private final PokerVoteRepository voteRepository;
    private final PokerRosterService rosterService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param ticketRepository  ticket persistence
     * @param roomRepository    room persistence, used to resolve the room and check the
     *                          facilitator identity
     * @param voteRepository    vote persistence, used at reveal time to load every cast vote
     * @param rosterService     rebroadcasts the named roster (resetting per-participant voted
     *                          state) when a new ticket opens
     * @param messagingTemplate used to broadcast {@code TICKET_CREATED}/{@code VOTES_REVEALED}
     *                          events
     * @param clock             the shared clock, overridable in tests
     */
    public PokerTicketService(
            final PokerTicketRepository ticketRepository,
            final PokerRoomRepository roomRepository,
            final PokerVoteRepository voteRepository,
            final PokerRosterService rosterService,
            final SimpMessagingTemplate messagingTemplate,
            final Clock clock) {
        this.ticketRepository = ticketRepository;
        this.roomRepository = roomRepository;
        this.voteRepository = voteRepository;
        this.rosterService = rosterService;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    /**
     * Creates a new ticket in a room, restricted to that room's facilitator, and broadcasts the
     * {@code TICKET_CREATED} event to every participant.
     *
     * @param roomId            the target room, scoped to the caller's tenant
     * @param title             the ticket's display title (already validated by the controller)
     * @param callerUserId      the caller's user id, resolved server-side from the bearer token
     * @param tenantId          the caller's tenant id, resolved server-side from the bearer token
     * @return the created ticket
     * @throws RoomNotFoundException          if the room does not exist for the caller's tenant
     * @throws TicketFacilitatorOnlyException  if the caller is not the room's facilitator
     * @throws ActiveTicketExistsException    if the room already has a {@code VOTING} ticket
     */
    @Transactional
    public TicketResponse create(
            final UUID roomId, final String title, final Long callerUserId, final Long tenantId) {
        requireFacilitator(roomId, callerUserId, tenantId);
        if (ticketRepository.existsByRoomIdAndStatus(roomId, PokerTicketStatus.VOTING)) {
            throw new ActiveTicketExistsException(roomId);
        }

        PokerTicket ticket = new PokerTicket(roomId, title, clock.instant());
        PokerTicket saved = ticketRepository.save(ticket);

        messagingTemplate.convertAndSend(
                PokerRoomDestinations.roomTopic(roomId),
                (Object) TicketCreatedEvent.of(roomId, saved.getId(), saved.getTitle(), saved.getCreatedAt()));
        // New ticket → nobody has voted on it yet; refresh the roster so every "voted" square resets.
        rosterService.broadcast(roomId);

        return toResponse(saved);
    }

    /**
     * Finds the currently open ({@code VOTING}) ticket for a room, if any.
     *
     * @param roomId   the target room, scoped to the caller's tenant
     * @param tenantId the caller's tenant id, resolved server-side from the bearer token
     * @return the open ticket, or empty if none is currently open
     * @throws RoomNotFoundException if the room does not exist for the caller's tenant
     */
    @Transactional(readOnly = true)
    public Optional<TicketResponse> getCurrent(final UUID roomId, final Long tenantId) {
        roomRepository.findByIdAndTenantId(roomId, tenantId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
        return ticketRepository.findByRoomIdAndStatus(roomId, PokerTicketStatus.VOTING)
                .map(PokerTicketService::toResponse);
    }

    /**
     * Reveals a ticket's votes, restricted to that room's facilitator (US09.2.2): computes the
     * consensus over every cast vote, transitions the ticket to {@code REVEALED}, and broadcasts
     * the {@code VOTES_REVEALED} event to every participant with the same content returned here.
     * Each vote is attributed to the voting participant's roster display name (E09 — classic
     * parity: replaces the pre-E09 anonymous {@code values} shape, letting the facilitator ask
     * whoever cast an extreme value to explain it) — resolved from the room's live roster at this
     * exact instant, never persisted as its own column.
     *
     * <p>No completeness gate — the facilitator may reveal at any {@code votedCount}, including
     * zero (Gate 1 decision, see the pivot-docs AC file): a ticket with no cast votes reveals with
     * an empty {@code attributedVotes} list and an all-{@code null} consensus.
     *
     * @param roomId       the target room, scoped to the caller's tenant
     * @param ticketId     the ticket to reveal
     * @param callerUserId the caller's user id, resolved server-side from the bearer token
     * @param tenantId     the caller's tenant id, resolved server-side from the bearer token
     * @return the revealed ticket, its attributed votes, and the computed consensus
     * @throws RoomNotFoundException            if the room does not exist for the caller's tenant
     * @throws TicketFacilitatorOnlyException    if the caller is not the room's facilitator
     * @throws TicketNotFoundException           if the ticket does not exist, or belongs to a
     *                                            different room than {@code roomId}
     * @throws TicketAlreadyRevealedException    if the ticket is not currently {@code VOTING}
     */
    @Transactional
    public RevealResponse reveal(
            final UUID roomId, final UUID ticketId, final Long callerUserId, final Long tenantId) {
        PokerRoom room = requireFacilitator(roomId, callerUserId, tenantId);
        PokerTicket ticket = findTicketInRoom(roomId, ticketId);
        if (ticket.getStatus() != PokerTicketStatus.VOTING) {
            throw new TicketAlreadyRevealedException(ticketId);
        }

        List<PokerVote> votes = voteRepository.findByTicketId(ticketId);
        List<String> values = votes.stream().map(PokerVote::getValue).toList();
        ConsensusResponse consensus =
                ConsensusCalculator.compute(values, PokerCardDeck.valuesFor(room.getSequence()));
        List<AttributedVoteResponse> attributedVotes = attributeVotes(roomId, votes);

        ticket.reveal(clock.instant());
        PokerTicket saved = ticketRepository.save(ticket);

        messagingTemplate.convertAndSend(
                PokerRoomDestinations.roomTopic(roomId),
                (Object) VotesRevealedEvent.of(
                        roomId, saved.getId(), attributedVotes, consensus, saved.getRevealedAt()));

        return new RevealResponse(
                saved.getId(),
                saved.getRoomId(),
                saved.getTitle(),
                saved.getStatus().name(),
                saved.getCreatedAt(),
                saved.getRevealedAt(),
                attributedVotes,
                consensus);
    }

    /**
     * Relaunches a round of voting on an already-revealed ticket, restricted to that room's
     * facilitator (US09.2.3): deletes every vote cast in the previous round, transitions the
     * ticket back to {@code VOTING}, and broadcasts {@code TICKET_RESET} plus a fresh roster
     * (every "has voted" square must clear, same treatment as a brand-new ticket).
     *
     * <p>Two independent capabilities (Gate 1 decision, see the pivot-docs AC file) — reset and
     * {@link #finalizeEstimate finalization} may be combined on the same ticket in any order, any
     * number of times, until finalized. A finalized ticket is a terminal state: neither this
     * method nor {@link #finalizeEstimate} may be called on it again.
     *
     * @param roomId       the target room, scoped to the caller's tenant
     * @param ticketId     the ticket to reset
     * @param callerUserId the caller's user id, resolved server-side from the bearer token
     * @param tenantId     the caller's tenant id, resolved server-side from the bearer token
     * @return the reset ticket, back to {@code VOTING} with {@code revealedAt == null}
     * @throws RoomNotFoundException           if the room does not exist for the caller's tenant
     * @throws TicketFacilitatorOnlyException  if the caller is not the room's facilitator
     * @throws TicketNotFoundException         if the ticket does not exist, or belongs to a
     *                                          different room than {@code roomId}
     * @throws TicketNotRevealedException      if the ticket is still {@code VOTING} (never
     *                                          revealed yet — nothing to reset)
     * @throws TicketAlreadyFinalizedException if the ticket already has a persisted final
     *                                         estimate (terminal state)
     */
    @Transactional
    public TicketResetResponse reset(
            final UUID roomId, final UUID ticketId, final Long callerUserId, final Long tenantId) {
        requireFacilitator(roomId, callerUserId, tenantId);
        PokerTicket ticket = findTicketInRoom(roomId, ticketId);
        requireRevealedAndNotFinalized(ticket);

        voteRepository.deleteByTicketId(ticketId);
        ticket.reset();
        PokerTicket saved = ticketRepository.save(ticket);

        messagingTemplate.convertAndSend(
                PokerRoomDestinations.roomTopic(roomId),
                (Object) TicketResetEvent.of(roomId, saved.getId()));
        rosterService.broadcast(roomId);

        return new TicketResetResponse(
                saved.getId(), saved.getRoomId(), saved.getTitle(), saved.getStatus().name(),
                saved.getCreatedAt(), saved.getRevealedAt());
    }

    /**
     * Persists the facilitator's chosen final estimate on an already-revealed ticket, restricted
     * to that room's facilitator (US09.2.3) — a deliberate choice among the room's own deck
     * values, not automatically the computed consensus ({@link ConsensusResponse#mean}/{@link
     * ConsensusResponse#median}/{@link ConsensusResponse#majority} are never persisted as-is;
     * see the pivot-docs AC file). Broadcasts {@code TICKET_FINALIZED} to every subscriber.
     *
     * <p>A terminal, one-time transition (Gate 1 decision) — once finalized, neither this method
     * nor {@link #reset} may be applied to the ticket again.
     *
     * @param roomId        the target room, scoped to the caller's tenant
     * @param ticketId      the ticket to finalize
     * @param finalEstimate the facilitator-chosen value — validated against the room's own deck
     *                      (E09 — {@link PokerCardDeck}), not a hardcoded one
     * @param callerUserId  the caller's user id, resolved server-side from the bearer token
     * @param tenantId      the caller's tenant id, resolved server-side from the bearer token
     * @return the finalized ticket, still {@code REVEALED}, with {@code finalEstimate} set
     * @throws RoomNotFoundException           if the room does not exist for the caller's tenant
     * @throws TicketFacilitatorOnlyException  if the caller is not the room's facilitator
     * @throws TicketNotFoundException         if the ticket does not exist, or belongs to a
     *                                          different room than {@code roomId}
     * @throws TicketNotRevealedException      if the ticket is still {@code VOTING}
     * @throws TicketAlreadyFinalizedException if the ticket already has a persisted final
     *                                         estimate (terminal state)
     * @throws IllegalArgumentException        if {@code finalEstimate} is not one of the room's
     *                                          own deck values — mapped to HTTP 400, message lists
     *                                          the accepted values (AC requirement)
     */
    @Transactional
    public TicketFinalizedResponse finalizeEstimate(
            final UUID roomId, final UUID ticketId, final String finalEstimate,
            final Long callerUserId, final Long tenantId) {
        PokerRoom room = requireFacilitator(roomId, callerUserId, tenantId);
        PokerTicket ticket = findTicketInRoom(roomId, ticketId);
        requireRevealedAndNotFinalized(ticket);

        List<String> deckValues = PokerCardDeck.valuesFor(room.getSequence());
        if (!deckValues.contains(finalEstimate)) {
            throw new IllegalArgumentException(
                    "Invalid final estimate, accepted values: " + deckValues);
        }

        ticket.finalizeEstimate(finalEstimate);
        PokerTicket saved = ticketRepository.save(ticket);

        messagingTemplate.convertAndSend(
                PokerRoomDestinations.roomTopic(roomId),
                (Object) TicketFinalizedEvent.of(roomId, saved.getId(), finalEstimate));

        return new TicketFinalizedResponse(
                saved.getId(), saved.getRoomId(), saved.getTitle(), saved.getStatus().name(),
                saved.getCreatedAt(), saved.getRevealedAt(), saved.getFinalEstimate());
    }

    /**
     * Lists every already-revealed ticket of a room, oldest first, each with its attributed votes
     * and consensus (E09 — end-of-session recap). Accessible to any authenticated caller in the
     * room's tenant, not facilitator-restricted — every ticket listed here was already broadcast
     * to every participant at its own reveal time, so nothing is newly disclosed.
     *
     * @param roomId   the target room, scoped to the caller's tenant
     * @param tenantId the caller's tenant id, resolved server-side from the bearer token
     * @return the room's recap — empty {@code tickets} if none has been revealed yet
     * @throws RoomNotFoundException if the room does not exist for the caller's tenant
     */
    @Transactional(readOnly = true)
    public RecapResponse recap(final UUID roomId, final Long tenantId) {
        PokerRoom room = roomRepository.findByIdAndTenantId(roomId, tenantId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
        List<String> deckValues = PokerCardDeck.valuesFor(room.getSequence());

        List<TicketRecapEntry> entries = ticketRepository
                .findByRoomIdAndStatusOrderByRevealedAtAsc(roomId, PokerTicketStatus.REVEALED)
                .stream()
                .map(ticket -> toRecapEntry(roomId, ticket, deckValues))
                .toList();

        return new RecapResponse(roomId, entries);
    }

    /**
     * Builds a single recap entry for an already-revealed ticket.
     *
     * @param roomId     the owning room's identifier (for roster attribution)
     * @param ticket     the revealed ticket
     * @param deckValues the room's own deck values, for the consensus majority tie-break
     * @return the corresponding {@link TicketRecapEntry}
     */
    private TicketRecapEntry toRecapEntry(
            final UUID roomId, final PokerTicket ticket, final List<String> deckValues) {
        List<PokerVote> votes = voteRepository.findByTicketId(ticket.getId());
        List<String> values = votes.stream().map(PokerVote::getValue).toList();
        return new TicketRecapEntry(
                ticket.getId(),
                ticket.getTitle(),
                ticket.getRevealedAt(),
                attributeVotes(roomId, votes),
                ConsensusCalculator.compute(values, deckValues),
                ticket.getFinalEstimate());
    }

    /**
     * Resolves a ticket by id, scoped to a room — shared by every ticket action that addresses an
     * existing ticket ({@link #reveal}, {@link #reset}, {@link #finalizeEstimate}). Collapses an
     * unknown ticket id and a ticket belonging to a different room into the same exception,
     * mirroring {@link #requireFacilitator}'s anti-enumeration posture.
     *
     * @param roomId   the room the ticket must belong to
     * @param ticketId the ticket to resolve
     * @return the resolved ticket
     * @throws TicketNotFoundException if the ticket does not exist, or belongs to a different
     *                                  room than {@code roomId}
     */
    private PokerTicket findTicketInRoom(final UUID roomId, final UUID ticketId) {
        return ticketRepository.findById(ticketId)
                .filter(candidate -> candidate.getRoomId().equals(roomId))
                .orElseThrow(() -> new TicketNotFoundException(ticketId));
    }

    /**
     * Asserts a ticket is eligible for {@link #reset}/{@link #finalizeEstimate} — already
     * revealed, and not yet finalized (terminal state) — shared by both actions (US09.2.3).
     *
     * @param ticket the ticket to check
     * @throws TicketNotRevealedException      if the ticket is still {@code VOTING}
     * @throws TicketAlreadyFinalizedException if the ticket already has a persisted final
     *                                         estimate
     */
    private void requireRevealedAndNotFinalized(final PokerTicket ticket) {
        if (ticket.getStatus() != PokerTicketStatus.REVEALED) {
            throw new TicketNotRevealedException(ticket.getId());
        }
        if (ticket.isFinalized()) {
            throw new TicketAlreadyFinalizedException(ticket.getId());
        }
    }

    /**
     * Attributes a list of votes to their voting participant's roster display name, resolved from
     * the room's live roster at this exact instant.
     *
     * @param roomId the owning room's identifier
     * @param votes  the votes to attribute
     * @return the attributed votes, no defined order
     */
    private List<AttributedVoteResponse> attributeVotes(final UUID roomId, final List<PokerVote> votes) {
        Map<String, String> namesByParticipantKey = rosterService.namesByParticipantKey(roomId);
        return votes.stream()
                .map(vote -> new AttributedVoteResponse(
                        namesByParticipantKey.getOrDefault(vote.getParticipantKey(), UNKNOWN_PARTICIPANT_NAME),
                        vote.getValue()))
                .toList();
    }

    /**
     * Resolves a room for the caller's tenant and asserts the caller is its facilitator — shared
     * by every facilitator-only ticket action ({@link #create}, {@link #reveal}, {@link #reset},
     * {@link #finalizeEstimate}).
     *
     * @param roomId       the target room, scoped to the caller's tenant
     * @param callerUserId the caller's user id
     * @param tenantId     the caller's tenant id
     * @return the resolved room
     * @throws RoomNotFoundException         if the room does not exist for the caller's tenant
     * @throws TicketFacilitatorOnlyException if the caller is not the room's facilitator
     */
    private PokerRoom requireFacilitator(final UUID roomId, final Long callerUserId, final Long tenantId) {
        PokerRoom room = roomRepository.findByIdAndTenantId(roomId, tenantId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
        if (!room.getFacilitatorUserId().equals(callerUserId)) {
            throw new TicketFacilitatorOnlyException(roomId);
        }
        return room;
    }

    /**
     * Maps a persisted ticket to its API response shape.
     *
     * @param ticket the persisted ticket
     * @return the corresponding {@link TicketResponse}
     */
    private static TicketResponse toResponse(final PokerTicket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getRoomId(),
                ticket.getTitle(),
                ticket.getStatus().name(),
                ticket.getCreatedAt());
    }
}
