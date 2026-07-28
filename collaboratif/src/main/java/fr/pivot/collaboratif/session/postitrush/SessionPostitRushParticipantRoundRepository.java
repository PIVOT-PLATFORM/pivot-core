package fr.pivot.collaboratif.session.postitrush;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SessionPostitRushParticipantRound}.
 */
public interface SessionPostitRushParticipantRoundRepository
        extends JpaRepository<SessionPostitRushParticipantRound, SessionPostitRushParticipantRoundId> {

    /**
     * Resolves a participant's score/combo row within a round.
     *
     * @param roundId       the owning round's UUID
     * @param participantId the participant's UUID
     * @return the row, if the participant has already registered at least one hit/miss this round
     */
    Optional<SessionPostitRushParticipantRound> findByIdRoundIdAndIdParticipantId(UUID roundId, UUID participantId);

    /**
     * Lists every participant's score/combo row for a round — the leaderboard/results source.
     *
     * @param roundId the owning round's UUID
     * @return every row of the round
     */
    List<SessionPostitRushParticipantRound> findAllByIdRoundId(UUID roundId);
}
