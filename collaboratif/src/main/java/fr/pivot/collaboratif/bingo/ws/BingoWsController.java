package fr.pivot.collaboratif.bingo.ws;

import fr.pivot.collaboratif.bingo.BingoMarkService;
import fr.pivot.collaboratif.bingo.dto.MarkCellRequest;
import fr.pivot.collaboratif.bingo.exception.InvalidCellException;
import fr.pivot.collaboratif.bingo.exception.RoomFinishedException;
import fr.pivot.collaboratif.bingo.exception.SpectatorCannotMarkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP {@code @MessageMapping} controller for marking/unmarking a Bingo cell (US47.1.1,
 * AC-47.1.1-07). Receives frames already authorized by {@link BingoChannelInterceptor} — this
 * class only needs to re-read the {@code access-token} header to resolve the caller's own grid
 * (SEC-02), since a message handler has no direct access to the interceptor's decision.
 */
@Controller
public class BingoWsController {

    private static final Logger LOG = LoggerFactory.getLogger(BingoWsController.class);

    private final BingoMarkService markService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Creates the controller with its required dependencies.
     *
     * @param markService       business logic for persisting a mark and detecting victory
     * @param messagingTemplate used to notify the emitting client of a rejected mark
     */
    public BingoWsController(final BingoMarkService markService, final SimpMessagingTemplate messagingTemplate) {
        this.markService = markService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Handles a mark/unmark request for one cell of the caller's own grid.
     *
     * @param roomId    the room id, from the destination
     * @param request   the mark payload — {@code cellIndex}/{@code marked} only (AC-47.1.1-12, no
     *                  "I won" field is ever read)
     * @param accessor  the STOMP frame's header accessor, used to read the {@code access-token}
     *                  native header and the session's principal (for targeted error delivery)
     */
    @MessageMapping("/collaboratif/bingo/{roomId}/mark")
    public void mark(
            @DestinationVariable final UUID roomId,
            @Payload final MarkCellRequest request,
            final StompHeaderAccessor accessor) {
        String accessToken = accessor.getFirstNativeHeader(BingoChannelInterceptor.ACCESS_TOKEN_HEADER);
        Principal user = accessor.getUser();

        Integer cellIndex = request.cellIndex();
        if (cellIndex == null || cellIndex < 0 || cellIndex > 24) {
            sendError(user, "INVALID_CELL", "Invalid cell index");
            return;
        }

        try {
            markService.mark(roomId, accessToken, cellIndex, request.marked());
        } catch (SpectatorCannotMarkException e) {
            sendError(user, "SPECTATOR_CANNOT_MARK", e.getMessage());
        } catch (RoomFinishedException e) {
            sendError(user, "ROOM_FINISHED", e.getMessage());
        } catch (InvalidCellException e) {
            sendError(user, "INVALID_CELL", e.getMessage());
        }
    }

    private void sendError(final Principal user, final String code, final String message) {
        if (user == null) {
            LOG.debug("Bingo mark rejected (code={}) but no principal to notify", code);
            return;
        }
        messagingTemplate.convertAndSendToUser(user.getName(), "/queue/errors", new BingoErrorPayload(message, code));
    }
}
