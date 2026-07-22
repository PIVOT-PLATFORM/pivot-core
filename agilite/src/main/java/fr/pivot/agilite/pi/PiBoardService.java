package fr.pivot.agilite.pi;

import fr.pivot.agilite.exception.PiNotFoundException;
import fr.pivot.agilite.pi.dto.BoardResponse;
import fr.pivot.agilite.pi.dto.DependencyResponse;
import fr.pivot.agilite.pi.dto.IterationResponse;
import fr.pivot.agilite.pi.dto.PiCycleTeamResponse;
import fr.pivot.agilite.pi.dto.TicketResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Aggregated Program Board read (US50.3.1) — one single payload combining iterations, Train
 * teams, tickets, and dependencies, avoiding the frontend having to issue four separate requests
 * to render the board.
 */
@Service
@Transactional(readOnly = true)
public class PiBoardService {

    private final PiCycleAccessService cycleAccessService;
    private final PiTicketRepository ticketRepository;
    private final PiDependencyRepository dependencyRepository;

    /**
     * Creates the service with all required dependencies.
     *
     * @param cycleAccessService   shared cycle-resolution/access-check helper
     * @param ticketRepository     repository for ticket persistence
     * @param dependencyRepository repository for dependency persistence
     */
    public PiBoardService(
            final PiCycleAccessService cycleAccessService,
            final PiTicketRepository ticketRepository,
            final PiDependencyRepository dependencyRepository) {
        this.cycleAccessService = cycleAccessService;
        this.ticketRepository = ticketRepository;
        this.dependencyRepository = dependencyRepository;
    }

    /**
     * Returns the full Program Board payload for a cycle.
     *
     * @param cycleId      the cycle UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the aggregated board response
     * @throws PiNotFoundException if the cycle does not exist, belongs to another tenant, or the
     *     caller has no access to it
     */
    public BoardResponse getBoard(final UUID cycleId, final Long callerUserId, final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        return new BoardResponse(
                cycle.getId(),
                cycle.getIterations().stream().map(IterationResponse::from).toList(),
                cycle.getTeams().stream().map(PiCycleTeamResponse::from).toList(),
                ticketRepository.findAllByCycleIdOrderByTicketOrderAsc(cycle.getId()).stream()
                        .map(TicketResponse::from)
                        .toList(),
                dependencyRepository.findAllByCycleId(cycle.getId()).stream()
                        .map(DependencyResponse::from)
                        .toList());
    }
}
