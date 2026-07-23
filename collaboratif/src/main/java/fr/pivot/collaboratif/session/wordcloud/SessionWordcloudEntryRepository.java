package fr.pivot.collaboratif.session.wordcloud;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SessionWordcloudEntry}.
 */
public interface SessionWordcloudEntryRepository extends JpaRepository<SessionWordcloudEntry, UUID> {

    /**
     * Finds the aggregated entry for a given word in a session, if any submission exists yet.
     *
     * @param sessionId the owning session's UUID
     * @param word      the normalized word
     * @return the entry, if present
     */
    Optional<SessionWordcloudEntry> findBySessionIdAndWord(UUID sessionId, String word);

    /**
     * Lists all aggregated words for a session, most frequent first.
     *
     * @param sessionId the owning session's UUID
     * @return the entries ordered by descending frequency
     */
    List<SessionWordcloudEntry> findAllBySessionIdOrderByFrequencyDesc(UUID sessionId);

    /**
     * Deletes the aggregated entry for a word (facilitator moderation).
     *
     * @param sessionId the owning session's UUID
     * @param word      the normalized word
     */
    void deleteBySessionIdAndWord(UUID sessionId, String word);
}
