package fr.pivot.collaboratif.bingo;

import fr.pivot.collaboratif.bingo.dto.BingoEvent;
import fr.pivot.collaboratif.bingo.dto.CellMarkedEvent;
import fr.pivot.collaboratif.bingo.dto.LineDto;
import fr.pivot.collaboratif.bingo.exception.InvalidCellException;
import fr.pivot.collaboratif.bingo.exception.RoomFinishedException;
import fr.pivot.collaboratif.bingo.exception.SpectatorCannotMarkException;
import fr.pivot.collaboratif.bingo.ws.BingoRoomDestinations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for marking/unmarking a Bingo grid cell and detecting victory (US47.1.1,
 * AC-47.1.1-07/09/10/11/12). Victory is computed <strong>exclusively</strong> from persisted
 * server-side state, never from any client-supplied signal (AC-47.1.1-12).
 */
@Service
public class BingoMarkService {

    private final BingoRoomRepository roomRepository;
    private final BingoGridRepository gridRepository;
    private final BingoGridCellRepository gridCellRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Constructs the service.
     *
     * @param roomRepository     room persistence — also owns the atomic OPEN-to-FINISHED
     *                           transition (AC-47.1.1-10/11)
     * @param gridRepository     grid persistence — resolves the caller's own grid from their grant
     * @param gridCellRepository cell persistence
     * @param messagingTemplate  STOMP broadcast template
     */
    public BingoMarkService(
            final BingoRoomRepository roomRepository,
            final BingoGridRepository gridRepository,
            final BingoGridCellRepository gridCellRepository,
            final SimpMessagingTemplate messagingTemplate) {
        this.roomRepository = roomRepository;
        this.gridRepository = gridRepository;
        this.gridCellRepository = gridCellRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Marks or unmarks one cell of the caller's own grid, broadcasts the resulting aggregate
     * {@code CELL_MARKED}, and — if this mark completes a line — atomically declares the caller
     * the room's unique winner and broadcasts {@code BINGO}.
     *
     * <p>The caller's identity is derived <strong>exclusively</strong> from
     * {@code (roomId, accessToken)} (SEC-02) — never from any identifier in the request payload.
     *
     * @param roomId      the room id
     * @param accessToken the caller's opaque access token, already authorized by {@code
     *                    BingoChannelInterceptor} at the STOMP frame level — re-resolved here to
     *                    find the caller's own grid
     * @param cellIndex   the target cell's position (0..24), already range-checked by the caller
     * @param marked      the requested marked state
     * @throws SpectatorCannotMarkException if the token resolves to no persisted grid (a
     *                                      spectator, AC-47.1.1-14)
     * @throws RoomFinishedException        if the room already transitioned to {@code FINISHED}
     *                                      (AC-47.1.1-19)
     * @throws InvalidCellException         if the resolved grid has no cell at {@code cellIndex}
     *                                      (defensive — the controller already range-checks)
     */
    @Transactional
    public void mark(final UUID roomId, final String accessToken, final int cellIndex, final boolean marked) {
        final BingoRoom room = roomRepository.findById(roomId).orElseThrow(RoomFinishedException::new);
        if (room.getStatus() == BingoRoomStatus.FINISHED) {
            throw new RoomFinishedException();
        }

        final String participantKey = BingoTokenHasher.hash(accessToken);
        final BingoGrid grid = gridRepository.findByRoomIdAndParticipantKey(roomId, participantKey)
                .orElseThrow(SpectatorCannotMarkException::new);
        final BingoGridCell cell = gridCellRepository.findByGridIdAndCellIndex(grid.getId(), cellIndex)
                .orElseThrow(InvalidCellException::new);

        cell.setMarked(marked);
        gridCellRepository.save(cell);

        final long markedCount = gridCellRepository.countByGridIdAndMarkedTrue(grid.getId());
        messagingTemplate.convertAndSend(
                BingoRoomDestinations.roomTopic(roomId),
                CellMarkedEvent.of(roomId, grid.getId(), (int) markedCount));

        if (marked) {
            detectAndDeclareVictory(roomId, grid);
        }
    }

    private void detectAndDeclareVictory(final UUID roomId, final BingoGrid grid) {
        final boolean[] markedState = buildMarkedState(grid.getId());
        final Optional<BingoWinningLine> win = BingoWinDetector.detect(markedState);
        if (win.isEmpty()) {
            return;
        }
        final BingoWinningLine line = win.get();
        final int affected = roomRepository.finishIfOpen(roomId, grid.getId(), line.kind().name(), line.index());
        if (affected != 1) {
            // Another participant's concurrent mark already won the race (AC-47.1.1-11) — this
            // mark itself was still persisted and CELL_MARKED already broadcast above, but no
            // second BINGO is ever announced.
            return;
        }
        messagingTemplate.convertAndSend(
                BingoRoomDestinations.roomTopic(roomId),
                BingoEvent.of(roomId, grid.getId(), grid.getDisplayName(), new LineDto(line.kind().name(), line.index())));
    }

    private boolean[] buildMarkedState(final UUID gridId) {
        List<BingoGridCell> cells = gridCellRepository.findByGridId(gridId).stream()
                .sorted(Comparator.comparingInt(BingoGridCell::getCellIndex))
                .toList();
        boolean[] marked = new boolean[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            marked[i] = cells.get(i).isMarked();
        }
        return marked;
    }
}
