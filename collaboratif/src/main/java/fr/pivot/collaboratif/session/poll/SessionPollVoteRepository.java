package fr.pivot.collaboratif.session.poll;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SessionPollVote}.
 */
public interface SessionPollVoteRepository extends JpaRepository<SessionPollVote, UUID> {

    /**
     * Finds a participant's active vote on a session, if any.
     *
     * @param sessionId     the owning session's UUID
     * @param participantId the participant's UUID
     * @return the vote, if the participant has already voted
     */
    Optional<SessionPollVote> findBySessionIdAndParticipantId(UUID sessionId, UUID participantId);

    /**
     * Lists all votes cast on a session, used to tally results.
     *
     * @param sessionId the owning session's UUID
     * @return every vote cast on the session
     */
    List<SessionPollVote> findAllBySessionId(UUID sessionId);
}
