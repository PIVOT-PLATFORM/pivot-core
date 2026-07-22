package fr.pivot.agilite.pi;

import fr.pivot.agilite.pi.PiDependencyCycleDetector.Edge;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PiDependencyCycleDetector} — pure, no Spring context (US50.3.2).
 */
class PiDependencyCycleDetectorTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();

    @Test
    void wouldCreateCycle_emptyGraph_returnsFalse() {
        assertThat(PiDependencyCycleDetector.wouldCreateCycle(List.of(), a, b)).isFalse();
    }

    @Test
    void wouldCreateCycle_directCycle_returnsTrue() {
        // A -> B already exists; adding B -> A would close a 2-node cycle.
        List<Edge> edges = List.of(new Edge(a, b));
        assertThat(PiDependencyCycleDetector.wouldCreateCycle(edges, b, a)).isTrue();
    }

    @Test
    void wouldCreateCycle_threeNodeCycle_returnsTrueOnThirdLink() {
        // A -> B -> C already exist; adding C -> A would close the cycle A->B->C->A.
        List<Edge> edges = List.of(new Edge(a, b), new Edge(b, c));
        assertThat(PiDependencyCycleDetector.wouldCreateCycle(edges, c, a)).isTrue();
    }

    @Test
    void wouldCreateCycle_longerChainNoCycle_returnsFalse() {
        // A -> B -> C -> D, adding A -> D is a valid shortcut, not a cycle.
        List<Edge> edges = List.of(new Edge(a, b), new Edge(b, c), new Edge(c, d));
        assertThat(PiDependencyCycleDetector.wouldCreateCycle(edges, a, d)).isFalse();
    }

    @Test
    void wouldCreateCycle_unrelatedBranch_returnsFalse() {
        // A -> B, C -> D are disjoint; adding B -> C does not create a cycle.
        List<Edge> edges = List.of(new Edge(a, b), new Edge(c, d));
        assertThat(PiDependencyCycleDetector.wouldCreateCycle(edges, b, c)).isFalse();
    }

    @Test
    void wouldCreateCycle_reverseOfExistingEdge_returnsTrue() {
        List<Edge> edges = List.of(new Edge(a, b));
        assertThat(PiDependencyCycleDetector.wouldCreateCycle(edges, b, a)).isTrue();
    }

    @Test
    void wouldCreateCycle_sameDirectionDuplicateAttempt_returnsFalse() {
        // Not this detector's job to reject duplicates (PiDependencyService does, separately) —
        // re-adding A -> B when it already exists does not itself form a NEW cycle.
        List<Edge> edges = List.of(new Edge(a, b));
        assertThat(PiDependencyCycleDetector.wouldCreateCycle(edges, a, b)).isFalse();
    }
}
