package fr.pivot.agilite.pi;

import fr.pivot.agilite.exception.PiNotFoundException;
import fr.pivot.agilite.exception.PiValidationException;
import fr.pivot.agilite.pi.dto.CreateTicketRequest;
import fr.pivot.agilite.pi.dto.TicketResponse;
import fr.pivot.agilite.pi.dto.UpdateTicketRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Business logic for Program Board ticket operations (US50.3.1).
 *
 * <p>All read operations are wrapped in a read-only transaction; write operations use a full
 * read-write transaction. Cycle access is enforced via {@link PiCycleAccessService}.
 */
@Service
@Transactional(readOnly = true)
public class PiTicketService {

    private static final int MAX_TITLE_LENGTH = 300;

    private final PiCycleAccessService cycleAccessService;
    private final PiTicketRepository ticketRepository;

    /**
     * Creates the service with all required dependencies.
     *
     * @param cycleAccessService shared cycle-resolution/access-check helper
     * @param ticketRepository   repository for ticket persistence
     */
    public PiTicketService(
            final PiCycleAccessService cycleAccessService, final PiTicketRepository ticketRepository) {
        this.cycleAccessService = cycleAccessService;
        this.ticketRepository = ticketRepository;
    }

    /**
     * Creates a new ticket on a cycle's Program Board.
     *
     * @param cycleId      the cycle UUID
     * @param request      the creation request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the created ticket response
     * @throws PiNotFoundException if the cycle does not exist, belongs to another tenant, or the
     *     caller has no access to it
     * @throws PiValidationException if the title is blank/too long, or {@code teamId}/{@code
     *     iterationId} do not belong to this cycle
     */
    @Transactional
    public TicketResponse create(
            final UUID cycleId, final CreateTicketRequest request, final Long callerUserId, final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        String title = validateTitle(request.title());
        validateCell(cycle, request.teamId(), request.iterationId());
        int order = nextOrder(cycle.getId(), request.teamId(), request.iterationId());
        PiTicket ticket = new PiTicket(
                cycle.getId(), request.type(), title, normalizeDescription(request.description()),
                request.teamId(), request.iterationId(), order);
        return TicketResponse.from(ticketRepository.save(ticket));
    }

    /**
     * Updates (or drag-drop moves) an existing ticket — see {@link UpdateTicketRequest}'s field
     * semantics.
     *
     * @param cycleId      the cycle UUID
     * @param ticketId     the ticket UUID
     * @param request      the update request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the updated ticket response
     * @throws PiNotFoundException if the cycle/ticket does not exist, belongs to another tenant,
     *     or the caller has no access to it
     * @throws PiValidationException if the title is blank/too long, or {@code teamId}/{@code
     *     iterationId} do not belong to this cycle
     */
    @Transactional
    public TicketResponse update(
            final UUID cycleId,
            final UUID ticketId,
            final UpdateTicketRequest request,
            final Long callerUserId,
            final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        PiTicket ticket = ticketRepository.findByIdAndCycleId(ticketId, cycle.getId())
                .orElseThrow(() -> new PiNotFoundException("PI ticket", ticketId));
        if (request.type() != null) {
            ticket.setType(request.type());
        }
        if (request.title() != null) {
            ticket.setTitle(validateTitle(request.title()));
        }
        if (request.description() != null) {
            ticket.setDescription(normalizeDescription(request.description()));
        }
        validateCell(cycle, request.teamId(), request.iterationId());
        ticket.setTeamId(request.teamId());
        ticket.setIterationId(request.iterationId());
        if (request.order() != null) {
            ticket.setTicketOrder(request.order());
        }
        return TicketResponse.from(ticket);
    }

    /**
     * Deletes a ticket — its dependencies (entering and leaving) cascade-delete at the database
     * level (US50.3.2).
     *
     * @param cycleId      the cycle UUID
     * @param ticketId     the ticket UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @throws PiNotFoundException if the cycle/ticket does not exist, belongs to another tenant,
     *     or the caller has no access to it
     */
    @Transactional
    public void delete(final UUID cycleId, final UUID ticketId, final Long callerUserId, final Long tenantId) {
        PiCycle cycle = cycleAccessService.resolveCycleForCaller(cycleId, callerUserId, tenantId);
        PiTicket ticket = ticketRepository.findByIdAndCycleId(ticketId, cycle.getId())
                .orElseThrow(() -> new PiNotFoundException("PI ticket", ticketId));
        ticketRepository.delete(ticket);
    }

    /**
     * Validates that a target cell's {@code teamId}/{@code iterationId} belong to the given
     * cycle — {@code null} is always valid (Train row / "Unplanned" column).
     *
     * @param cycle       the resolved cycle
     * @param teamId      the target team, or {@code null}
     * @param iterationId the target iteration, or {@code null}
     * @throws PiValidationException {@code INVALID_CELL} if either reference belongs to another
     *     cycle
     */
    private void validateCell(final PiCycle cycle, final UUID teamId, final UUID iterationId) {
        if (teamId != null && cycle.getTeams().stream().noneMatch(team -> team.getId().equals(teamId))) {
            throw new PiValidationException("INVALID_CELL", "teamId does not belong to this cycle: " + teamId);
        }
        if (iterationId != null
                && cycle.getIterations().stream().noneMatch(it -> it.getId().equals(iterationId))) {
            throw new PiValidationException("INVALID_CELL", "iterationId does not belong to this cycle: " + iterationId);
        }
    }

    /**
     * Computes the next display order within a target cell.
     *
     * @param cycleId     the cycle UUID
     * @param teamId      the target team, or {@code null}
     * @param iterationId the target iteration, or {@code null}
     * @return the next order (max existing + 1, or 0 if the cell is empty)
     */
    private int nextOrder(final UUID cycleId, final UUID teamId, final UUID iterationId) {
        Integer max = ticketRepository.findMaxOrderInCell(cycleId, teamId, iterationId);
        return max == null ? 0 : max + 1;
    }

    /**
     * Validates a ticket title.
     *
     * @param title the candidate title
     * @return the trimmed, validated title
     * @throws PiValidationException {@code INVALID_TITLE} if blank or too long
     */
    private String validateTitle(final String title) {
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TITLE_LENGTH) {
            throw new PiValidationException("INVALID_TITLE", "title must be 1-" + MAX_TITLE_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * Normalizes an optional description — trims, and maps a blank result to {@code null}.
     *
     * @param description the raw description, or {@code null}
     * @return the normalized description, or {@code null}
     */
    private String normalizeDescription(final String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
