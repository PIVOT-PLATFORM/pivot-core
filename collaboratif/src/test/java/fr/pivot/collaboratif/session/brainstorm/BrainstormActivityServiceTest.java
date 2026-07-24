package fr.pivot.collaboratif.session.brainstorm;

import fr.pivot.collaboratif.exception.InvalidSessionStatusException;
import fr.pivot.collaboratif.exception.SessionForbiddenException;
import fr.pivot.collaboratif.exception.SessionNotFoundException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import fr.pivot.collaboratif.session.brainstorm.dto.BrainstormCardDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrainstormActivityServiceTest {

    @Mock
    private SessionBrainstormCardRepository cardRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private BrainstormActivityService service;

    @BeforeEach
    void setUp() {
        service = new BrainstormActivityService(cardRepository, messagingTemplate);
    }

    private Session liveSession() {
        Session session = new Session(1L, null, "T", SessionType.BRAINSTORM, "ABCDEF", "{}", 10L, Instant.now());
        session.setStatus(SessionStatus.LIVE);
        return session;
    }

    private SessionBrainstormCard card(final UUID id, final UUID sessionId, final UUID participantId) {
        SessionBrainstormCard c = new SessionBrainstormCard(
                sessionId, participantId, "idea", BrainstormCardColor.YELLOW, Instant.now());
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    // --- add ----------------------------------------------------------------------------------

    @Test
    void addCardRejectsWhenSessionIsNotLive() {
        Session session = new Session(1L, null, "T", SessionType.BRAINSTORM, "ABCDEF", "{}", 10L, Instant.now());

        assertThatThrownBy(() -> service.addCard(session, UUID.randomUUID(), "idea", "YELLOW"))
                .isInstanceOf(InvalidSessionStatusException.class);
        verify(cardRepository, never()).save(any());
    }

    @Test
    void addCardRejectsEmptyText() {
        assertThatThrownBy(() -> service.addCard(liveSession(), UUID.randomUUID(), "   ", "YELLOW"))
                .isInstanceOf(SessionValidationException.class);
    }

    @Test
    void addCardRejectsAnUnknownColor() {
        assertThatThrownBy(() -> service.addCard(liveSession(), UUID.randomUUID(), "idea", "TURQUOISE"))
                .isInstanceOf(SessionValidationException.class);
    }

    @Test
    void addCardPersistsAndBroadcasts() {
        Session session = liveSession();
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.addCard(session, UUID.randomUUID(), "  idea ", "pink");

        verify(cardRepository).save(any());
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    // --- edit ---------------------------------------------------------------------------------

    @Test
    void editCardRejectsAnUnknownCard() {
        Session session = liveSession();
        UUID cardId = UUID.randomUUID();
        when(cardRepository.findByIdAndSessionId(cardId, session.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.editCard(session, UUID.randomUUID(), cardId, "x", "BLUE"))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void editCardRejectsANonOwnerWith403() {
        Session session = liveSession();
        UUID cardId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(cardRepository.findByIdAndSessionId(cardId, session.getId()))
                .thenReturn(Optional.of(card(cardId, session.getId(), owner)));

        assertThatThrownBy(() -> service.editCard(session, other, cardId, "x", "BLUE"))
                .isInstanceOf(SessionForbiddenException.class)
                .satisfies(ex -> assertThat(((SessionForbiddenException) ex).getCode()).isEqualTo("NOT_CARD_OWNER"));
        verify(cardRepository, never()).save(any());
    }

    @Test
    void editCardUpdatesAndBroadcastsForTheOwner() {
        Session session = liveSession();
        UUID cardId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        SessionBrainstormCard c = card(cardId, session.getId(), owner);
        when(cardRepository.findByIdAndSessionId(cardId, session.getId())).thenReturn(Optional.of(c));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.editCard(session, owner, cardId, "updated", "GREEN");

        assertThat(c.getText()).isEqualTo("updated");
        assertThat(c.getColor()).isEqualTo(BrainstormCardColor.GREEN);
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    // --- delete -------------------------------------------------------------------------------

    @Test
    void deleteCardRejectsANonOwnerWith403() {
        Session session = liveSession();
        UUID cardId = UUID.randomUUID();
        when(cardRepository.findByIdAndSessionId(cardId, session.getId()))
                .thenReturn(Optional.of(card(cardId, session.getId(), UUID.randomUUID())));

        assertThatThrownBy(() -> service.deleteCard(session, UUID.randomUUID(), cardId))
                .isInstanceOf(SessionForbiddenException.class);
        verify(cardRepository, never()).delete(any());
    }

    @Test
    void deleteCardDeletesAndBroadcastsForTheOwner() {
        Session session = liveSession();
        UUID cardId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(cardRepository.findByIdAndSessionId(cardId, session.getId()))
                .thenReturn(Optional.of(card(cardId, session.getId(), owner)));

        service.deleteCard(session, owner, cardId);

        verify(cardRepository).delete(any());
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    // --- categorize ---------------------------------------------------------------------------

    @Test
    void categorizeCardRejectsAnUnknownCard() {
        UUID sessionId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        when(cardRepository.findByIdAndSessionId(cardId, sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.categorizeCard(sessionId, cardId, "Ideas"))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void categorizeCardSetsTheCategoryAndBroadcasts() {
        UUID sessionId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        SessionBrainstormCard c = card(cardId, sessionId, UUID.randomUUID());
        when(cardRepository.findByIdAndSessionId(cardId, sessionId)).thenReturn(Optional.of(c));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.categorizeCard(sessionId, cardId, "  Ideas ");

        assertThat(c.getCategory()).isEqualTo("Ideas");
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void categorizeCardClearsTheCategoryOnBlank() {
        UUID sessionId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        SessionBrainstormCard c = card(cardId, sessionId, UUID.randomUUID());
        c.categorize("Old");
        when(cardRepository.findByIdAndSessionId(cardId, sessionId)).thenReturn(Optional.of(c));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.categorizeCard(sessionId, cardId, "   ");

        assertThat(c.getCategory()).isNull();
    }

    // --- list ---------------------------------------------------------------------------------

    @Test
    void getCardsReturnsMappedCards() {
        UUID sessionId = UUID.randomUUID();
        when(cardRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId))
                .thenReturn(List.of(card(UUID.randomUUID(), sessionId, UUID.randomUUID())));

        List<BrainstormCardDto> result = service.getCards(sessionId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).color()).isEqualTo(BrainstormCardColor.YELLOW);
    }
}
