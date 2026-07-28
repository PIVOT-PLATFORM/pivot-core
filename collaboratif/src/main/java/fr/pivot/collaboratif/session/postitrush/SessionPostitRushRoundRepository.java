package fr.pivot.collaboratif.session.postitrush;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SessionPostitRushRound}.
 */
public interface SessionPostitRushRoundRepository extends JpaRepository<SessionPostitRushRound, UUID> {

    /**
     * Resolves the currently active (not-yet-ended) round of a session, if any.
     *
     * @param sessionId the owning session's UUID
     * @return the active round
     */
    Optional<SessionPostitRushRound> findBySessionIdAndEndedAtIsNull(UUID sessionId);

    /**
     * Resolves the most recently started round of a session (active or ended) — used for the
     * results endpoint once a round has ended.
     *
     * @param sessionId the owning session's UUID
     * @return the latest round
     */
    Optional<SessionPostitRushRound> findFirstBySessionIdOrderByRoundNumberDesc(UUID sessionId);

    /**
     * Counts rounds already played for a session — used to compute the next round number.
     *
     * @param sessionId the owning session's UUID
     * @return the round count
     */
    long countBySessionId(UUID sessionId);

    /**
     * Lists every currently active round across every session — scanned by the scheduler
     * (US47.2.1), the same polling pattern as {@code StandupTimerScheduler}/{@code
     * RetroPhaseScheduler}.
     *
     * @return every active round
     */
    List<SessionPostitRushRound> findAllByEndedAtIsNull();
}
