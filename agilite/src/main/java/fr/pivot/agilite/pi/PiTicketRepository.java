package fr.pivot.agilite.pi;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PiTicket} entities (US50.3.1).
 */
public interface PiTicketRepository extends JpaRepository<PiTicket, UUID> {

    /**
     * Finds a ticket by id, scoped to the expected cycle.
     *
     * @param id      the ticket UUID
     * @param cycleId the expected owning cycle's UUID
     * @return the matching ticket, or empty if not found or owned by another cycle
     */
    Optional<PiTicket> findByIdAndCycleId(UUID id, UUID cycleId);

    /**
     * Lists every ticket of a cycle, ordered by {@code ticketOrder} — the Program Board applies
     * its own team/iteration cell grouping client-side from this flat, fully-ordered list.
     *
     * @param cycleId the owning cycle's UUID
     * @return the cycle's tickets, ordered by {@code ticketOrder} ascending
     */
    List<PiTicket> findAllByCycleIdOrderByTicketOrderAsc(UUID cycleId);

    /**
     * Returns the highest {@code ticketOrder} currently used in a given board cell (team x
     * iteration), or {@code null} if the cell is empty.
     *
     * @param cycleId     the owning cycle's UUID
     * @param teamId      the target team, or {@code null} for the Train row
     * @param iterationId the target iteration, or {@code null} for "Unplanned"
     * @return the max order in that cell, or {@code null} if empty
     */
    @Query("SELECT MAX(t.ticketOrder) FROM PiTicket t WHERE t.cycleId = :cycleId "
            + "AND ((:teamId IS NULL AND t.teamId IS NULL) OR t.teamId = :teamId) "
            + "AND ((:iterationId IS NULL AND t.iterationId IS NULL) OR t.iterationId = :iterationId)")
    Integer findMaxOrderInCell(
            @Param("cycleId") UUID cycleId,
            @Param("teamId") UUID teamId,
            @Param("iterationId") UUID iterationId);
}
