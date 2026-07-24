package fr.pivot.collaboratif.session.wordcloud;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repository for {@link SessionWordcloudSubmission}.
 */
public interface SessionWordcloudSubmissionRepository extends JpaRepository<SessionWordcloudSubmission, UUID> {

    /**
     * Counts how many words a participant has already submitted in a session — enforces
     * {@code config.maxWordsPerParticipant}.
     *
     * @param sessionId     the owning session's UUID
     * @param participantId the participant's id
     * @return the number of prior submissions
     */
    long countBySessionIdAndParticipantId(UUID sessionId, UUID participantId);

    /**
     * Deletes every submission for a word in a session (facilitator moderation cascade).
     *
     * @param sessionId the owning session's UUID
     * @param word      the normalized word
     */
    void deleteBySessionIdAndWord(UUID sessionId, String word);
}
