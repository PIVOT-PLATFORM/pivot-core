package fr.pivot.agilite.poker.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.pivot.agilite.poker.ticket.PokerTicket;
import fr.pivot.agilite.poker.ticket.PokerTicketRepository;
import fr.pivot.agilite.poker.ticket.PokerTicketStatus;
import fr.pivot.agilite.poker.vote.PokerVote;
import fr.pivot.agilite.poker.vote.PokerVoteRepository;
import fr.pivot.agilite.poker.ws.dto.RosterParticipant;
import fr.pivot.agilite.poker.ws.dto.RosterUpdatedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** Unit tests for {@link PokerRosterService} (E09 — named roster broadcast). */
@ExtendWith(MockitoExtension.class)
class PokerRosterServiceTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String KEY_ALICE = "key-alice";
    private static final String KEY_BOB = "key-bob";

    @Mock
    private PokerParticipantRegistryService registryService;

    @Mock
    private PokerTicketRepository ticketRepository;

    @Mock
    private PokerVoteRepository voteRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private PokerRosterService rosterService() {
        return new PokerRosterService(registryService, ticketRepository, voteRepository, messagingTemplate);
    }

    @Test
    void broadcast_marksOnlyParticipantsWhoVotedOnTheActiveTicket() {
        when(registryService.roster(ROOM_ID)).thenReturn(List.of(
                new PokerParticipantRegistryService.RosterMember(KEY_ALICE, "Alice", ParticipantRole.JOUEUR),
                new PokerParticipantRegistryService.RosterMember(KEY_BOB, "Bob", ParticipantRole.JOUEUR)));
        PokerTicket ticket = new PokerTicket(ROOM_ID, "Estimate", Instant.parse("2026-07-10T10:00:00Z"));
        setTicketId(ticket, TICKET_ID);
        when(ticketRepository.findByRoomIdAndStatus(ROOM_ID, PokerTicketStatus.VOTING))
                .thenReturn(Optional.of(ticket));
        when(voteRepository.findByTicketId(TICKET_ID)).thenReturn(List.of(
                new PokerVote(TICKET_ID, KEY_ALICE, "5", Instant.parse("2026-07-10T10:00:00Z"))));

        rosterService().broadcast(ROOM_ID);

        RosterUpdatedEvent event = captureEvent();
        assertThat(event.type()).isEqualTo(RosterUpdatedEvent.TYPE);
        assertThat(event.participants())
                .extracting(RosterParticipant::name, RosterParticipant::hasVoted)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Alice", true),
                        org.assertj.core.groups.Tuple.tuple("Bob", false));
    }

    @Test
    void broadcast_withNoActiveTicket_marksNobodyAsVoted() {
        when(registryService.roster(ROOM_ID)).thenReturn(List.of(
                new PokerParticipantRegistryService.RosterMember(KEY_ALICE, "Alice", ParticipantRole.JOUEUR)));
        when(ticketRepository.findByRoomIdAndStatus(ROOM_ID, PokerTicketStatus.VOTING))
                .thenReturn(Optional.empty());

        rosterService().broadcast(ROOM_ID);

        assertThat(captureEvent().participants())
                .singleElement()
                .satisfies(p -> assertThat(p.hasVoted()).isFalse());
    }

    private RosterUpdatedEvent captureEvent() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/agilite/poker/" + ROOM_ID), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(RosterUpdatedEvent.class);
        return (RosterUpdatedEvent) payload.getValue();
    }

    private static void setTicketId(final PokerTicket ticket, final UUID id) {
        try {
            var field = PokerTicket.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(ticket, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
