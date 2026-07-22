package fr.pivot.agilite.pi;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.pi.dto.BoardResponse;
import fr.pivot.agilite.web.AgiliteApiPaths;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing the aggregated Program Board read under {@code
 * /pi/cycles/{cycleId}/board} (US50.3.1).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} by {@link fr.pivot.agilite.context.RequestPrincipalResolver}.
 *
 * <p>The full path (including the application context) is {@code
 * /api/agilite/pi/cycles/{cycleId}/board}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/pi/cycles")
public class PiBoardController {

    private final PiBoardService boardService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param boardService the Program Board read service (US50.3.1)
     */
    public PiBoardController(final PiBoardService boardService) {
        this.boardService = boardService;
    }

    /**
     * Returns the full Program Board payload for a cycle.
     *
     * @param cycleId   the cycle UUID from the path
     * @param principal the resolved caller identity
     * @return the aggregated board response, or HTTP 404 if not found or inaccessible
     */
    @GetMapping("/{cycleId}/board")
    public BoardResponse getBoard(@PathVariable final UUID cycleId, final RequestPrincipal principal) {
        return boardService.getBoard(cycleId, principal.userId(), principal.tenantId());
    }
}
