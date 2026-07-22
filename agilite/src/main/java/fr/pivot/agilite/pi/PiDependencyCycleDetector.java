package fr.pivot.agilite.pi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, persistence-free anti-cycle check for {@link PiDependency} edges (US50.3.2).
 *
 * <p>Reference algorithm: the PouetPouet POC's {@code wouldCreateDependencyCycle}/{@code
 * validateDeps} (adapted from the Roadmap module, itself unimplemented in PIVOT — see the Gate 1
 * "Architecture — algorithme anti-cycle" decision in {@code us-dependances-program-board.md}).
 * Deliberately decoupled from JPA/Spring, same posture as {@link PiIterationGenerator}/{@code
 * fr.pivot.agilite.wheel.WeightedEntrySelector} — {@code PiDependencyService} loads the cycle's
 * existing edges via {@code PiDependencyRepository#findAllByCycleId} and calls this class before
 * every insert.
 */
public final class PiDependencyCycleDetector {

    private PiDependencyCycleDetector() {
    }

    /**
     * A directed dependency edge, {@code from} depends on {@code to} being resolved first — same
     * semantics as {@link PiDependency#getFromTicketId()}/{@link PiDependency#getToTicketId()}.
     *
     * @param fromTicketId the edge's tail
     * @param toTicketId   the edge's head
     */
    public record Edge(UUID fromTicketId, UUID toTicketId) {
    }

    /**
     * Checks whether adding the edge {@code fromId -> toId} to the given existing edge set would
     * create a cycle.
     *
     * <p>A cycle would be created exactly when {@code fromId} is already reachable from {@code
     * toId} by following existing edges — depth-first search over the adjacency built from {@code
     * existingEdges}, {@code O(tickets + dependencies)} per call.
     *
     * @param existingEdges the cycle's current dependency edges (before this candidate insert)
     * @param fromId        the candidate edge's tail
     * @param toId          the candidate edge's head
     * @return {@code true} if inserting {@code fromId -> toId} would create a cycle
     */
    public static boolean wouldCreateCycle(final List<Edge> existingEdges, final UUID fromId, final UUID toId) {
        Map<UUID, List<UUID>> adjacency = new HashMap<>();
        for (Edge edge : existingEdges) {
            adjacency.computeIfAbsent(edge.fromTicketId(), key -> new ArrayList<>()).add(edge.toTicketId());
        }
        Set<UUID> visited = new HashSet<>();
        return reaches(adjacency, visited, toId, fromId);
    }

    /**
     * Depth-first search: does {@code nodeId} reach {@code targetId} by following {@code
     * adjacency} edges?
     *
     * @param adjacency the adjacency list built from the existing edges
     * @param visited   nodes already visited in this search — mutated, guards against revisiting
     * @param nodeId    the current node
     * @param targetId  the node being searched for
     * @return {@code true} if {@code targetId} is reachable from {@code nodeId}
     */
    private static boolean reaches(
            final Map<UUID, List<UUID>> adjacency,
            final Set<UUID> visited,
            final UUID nodeId,
            final UUID targetId) {
        if (nodeId.equals(targetId)) {
            return true;
        }
        if (!visited.add(nodeId)) {
            return false;
        }
        for (UUID next : adjacency.getOrDefault(nodeId, List.of())) {
            if (reaches(adjacency, visited, next, targetId)) {
                return true;
            }
        }
        return false;
    }
}
