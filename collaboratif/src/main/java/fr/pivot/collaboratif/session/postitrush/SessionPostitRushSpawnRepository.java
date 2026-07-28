package fr.pivot.collaboratif.session.postitrush;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SessionPostitRushSpawn}.
 */
public interface SessionPostitRushSpawnRepository extends JpaRepository<SessionPostitRushSpawn, UUID> {

    /**
     * Resolves a spawn scoped to its owning round — used by {@code click} so a postitId from
     * another round/session can never resolve (cross-room isolation).
     *
     * @param id      the spawn's UUID
     * @param roundId the owning round's UUID
     * @return the spawn, if it belongs to that round
     */
    Optional<SessionPostitRushSpawn> findByIdAndRoundId(UUID id, UUID roundId);

    /**
     * Lists every still-unresolved (unclaimed and unexpired) spawn of a round — the scheduler
     * filters these in-memory for lifespan expiry, and the state endpoint filters them for
     * liveness at read time.
     *
     * @param roundId the owning round's UUID
     * @return the unresolved spawns
     */
    List<SessionPostitRushSpawn> findAllByRoundIdAndClaimedByIsNullAndExpiredFalse(UUID roundId);
}
