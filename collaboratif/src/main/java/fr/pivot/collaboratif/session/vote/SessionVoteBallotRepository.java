package fr.pivot.collaboratif.session.vote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link SessionVoteBallot}.
 */
public interface SessionVoteBallotRepository extends JpaRepository<SessionVoteBallot, UUID> {

    /**
     * Returns whether a participant has already cast a ballot on a session (the one-vote guard).
     *
     * @param sessionId     the owning session's UUID
     * @param participantId the participant's UUID
     * @return {@code true} if a ballot already exists
     */
    boolean existsBySessionIdAndParticipantId(UUID sessionId, UUID participantId);

    /**
     * Counts the ballots cast on a session (live submission count, no tallies revealed).
     *
     * @param sessionId the owning session's UUID
     * @return the number of ballots
     */
    long countBySessionId(UUID sessionId);

    /**
     * Lists every ballot cast on a session, used to tally results after the vote closes.
     *
     * @param sessionId the owning session's UUID
     * @return every ballot on the session
     */
    List<SessionVoteBallot> findAllBySessionId(UUID sessionId);
}
