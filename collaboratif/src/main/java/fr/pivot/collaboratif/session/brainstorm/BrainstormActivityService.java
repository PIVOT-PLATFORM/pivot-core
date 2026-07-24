package fr.pivot.collaboratif.session.brainstorm;

import fr.pivot.collaboratif.exception.InvalidSessionStatusException;
import fr.pivot.collaboratif.exception.SessionForbiddenException;
import fr.pivot.collaboratif.exception.SessionNotFoundException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import fr.pivot.collaboratif.session.brainstorm.dto.BrainstormCardDto;
import fr.pivot.collaboratif.session.brainstorm.dto.CardAddedEvent;
import fr.pivot.collaboratif.session.brainstorm.dto.CardRemovedEvent;
import fr.pivot.collaboratif.session.brainstorm.dto.CardUpdatedEvent;
import fr.pivot.collaboratif.session.ws.SessionDestinations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for the BRAINSTORM activity type (US19.3.4) — participant post-it CRUD (author
 * may edit/delete only their own cards) plus facilitator categorization. Every mutation broadcasts
 * on the shared session topic; no creation-time config.
 */
@Service
public class BrainstormActivityService {

    private static final int MAX_TEXT_LENGTH = 280;

    private final SessionBrainstormCardRepository cardRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Creates the service with its required dependencies.
     *
     * @param cardRepository    repository for cards
     * @param messagingTemplate STOMP broadcaster
     */
    public BrainstormActivityService(
            final SessionBrainstormCardRepository cardRepository,
            final SimpMessagingTemplate messagingTemplate) {
        this.cardRepository = cardRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Returns whether this service handles the given session type.
     *
     * @param type the session type
     * @return {@code true} for {@link SessionType#BRAINSTORM}
     */
    public boolean supports(final SessionType type) {
        return type == SessionType.BRAINSTORM;
    }

    /**
     * Adds a card from a participant (US19.3.4) and broadcasts {@code CARD_ADDED}.
     *
     * @param session       the LIVE BRAINSTORM session
     * @param participantId the authoring participant's id
     * @param rawText       the raw card text
     * @param rawColor      the requested colour name
     * @throws InvalidSessionStatusException if the session is not a LIVE BRAINSTORM
     * @throws SessionValidationException    if the text or colour is invalid
     */
    @Transactional
    public void addCard(
            final Session session, final UUID participantId, final String rawText, final String rawColor) {
        requireLiveBrainstorm(session);
        String text = validateText(rawText);
        BrainstormCardColor color = parseColor(rawColor);

        SessionBrainstormCard card = cardRepository.save(
                new SessionBrainstormCard(session.getId(), participantId, text, color, Instant.now()));
        broadcast(session.getId(), new CardAddedEvent(session.getId(), toDto(card)));
    }

    /**
     * Edits a card the caller authored (US19.3.4) and broadcasts {@code CARD_UPDATED}.
     *
     * @param session       the LIVE BRAINSTORM session
     * @param participantId the acting participant's id (must be the card's author)
     * @param cardId        the card to edit
     * @param rawText       the new text
     * @param rawColor      the new colour name
     * @throws InvalidSessionStatusException if the session is not a LIVE BRAINSTORM
     * @throws SessionNotFoundException      if the card does not belong to this session
     * @throws SessionForbiddenException     if the caller is not the card's author
     * @throws SessionValidationException    if the text or colour is invalid
     */
    @Transactional
    public void editCard(
            final Session session, final UUID participantId, final UUID cardId,
            final String rawText, final String rawColor) {
        requireLiveBrainstorm(session);
        SessionBrainstormCard card = requireOwnedCard(session.getId(), cardId, participantId);
        card.edit(validateText(rawText), parseColor(rawColor));
        card = cardRepository.save(card);
        broadcast(session.getId(), new CardUpdatedEvent(session.getId(), toDto(card)));
    }

    /**
     * Deletes a card the caller authored (US19.3.4) and broadcasts {@code CARD_REMOVED}.
     *
     * @param session       the LIVE BRAINSTORM session
     * @param participantId the acting participant's id (must be the card's author)
     * @param cardId        the card to delete
     * @throws InvalidSessionStatusException if the session is not a LIVE BRAINSTORM
     * @throws SessionNotFoundException      if the card does not belong to this session
     * @throws SessionForbiddenException     if the caller is not the card's author
     */
    @Transactional
    public void deleteCard(final Session session, final UUID participantId, final UUID cardId) {
        requireLiveBrainstorm(session);
        SessionBrainstormCard card = requireOwnedCard(session.getId(), cardId, participantId);
        cardRepository.delete(card);
        broadcast(session.getId(), new CardRemovedEvent(session.getId(), cardId));
    }

    /**
     * Sets (or clears) a card's grouping category — facilitator action (US19.3.4) — and broadcasts
     * {@code CARD_UPDATED}. Any card may be categorized regardless of author.
     *
     * @param sessionId the owning session's UUID
     * @param cardId    the card to categorize
     * @param category  the grouping label, or {@code null}/blank to clear it
     * @throws SessionNotFoundException if the card does not belong to this session
     */
    @Transactional
    public void categorizeCard(final UUID sessionId, final UUID cardId, final String category) {
        SessionBrainstormCard card = cardRepository.findByIdAndSessionId(cardId, sessionId)
                .orElseThrow(SessionNotFoundException::new);
        String normalized = category == null || category.isBlank() ? null : category.trim();
        card.categorize(normalized);
        card = cardRepository.save(card);
        broadcast(sessionId, new CardUpdatedEvent(sessionId, toDto(card)));
    }

    /**
     * Lists a session's cards, oldest first (US19.3.4).
     *
     * @param sessionId the owning session's UUID
     * @return the cards
     */
    @Transactional(readOnly = true)
    public List<BrainstormCardDto> getCards(final UUID sessionId) {
        return cardRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(BrainstormActivityService::toDto)
                .toList();
    }

    private SessionBrainstormCard requireOwnedCard(
            final UUID sessionId, final UUID cardId, final UUID participantId) {
        SessionBrainstormCard card = cardRepository.findByIdAndSessionId(cardId, sessionId)
                .orElseThrow(SessionNotFoundException::new);
        if (!card.getParticipantId().equals(participantId)) {
            throw new SessionForbiddenException("NOT_CARD_OWNER", "Only the card's author may modify it");
        }
        return card;
    }

    private String validateText(final String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty() || text.length() > MAX_TEXT_LENGTH) {
            throw new SessionValidationException("INVALID_CARD", "Card text must be 1 to 280 characters");
        }
        return text;
    }

    private BrainstormCardColor parseColor(final String rawColor) {
        if (rawColor == null) {
            throw new SessionValidationException("INVALID_CARD", "Unknown card colour");
        }
        try {
            return BrainstormCardColor.valueOf(rawColor.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SessionValidationException("INVALID_CARD", "Unknown card colour");
        }
    }

    private void requireLiveBrainstorm(final Session session) {
        if (session.getType() != SessionType.BRAINSTORM || session.getStatus() != SessionStatus.LIVE) {
            throw new InvalidSessionStatusException("Session is not a LIVE brainstorm");
        }
    }

    private void broadcast(final UUID sessionId, final Object event) {
        messagingTemplate.convertAndSend(SessionDestinations.topicFor(sessionId), event);
    }

    private static BrainstormCardDto toDto(final SessionBrainstormCard card) {
        return new BrainstormCardDto(
                card.getId(), card.getText(), card.getColor(), card.getCategory(),
                card.getParticipantId(), card.getCreatedAt());
    }
}
